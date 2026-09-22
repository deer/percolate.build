package build.percolate.maven.exec;

/*-
 * #%L
 * Percolate Maven Exec Plugin
 * %%
 * Copyright (C) 2026 Reed von Redwitz
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import build.percolate.core.ModuleGraphClassifier;
import build.percolate.core.ParameterOverrides;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Forks a JVM with a properly classified module-path for JPMS-aware self-hosting builds.
 *
 * <p>Collects jars from either the plugin's own class realm ({@code scope=plugin}) or from the
 * Maven project's compile / runtime / test classpath, runs them through
 * {@link ModuleGraphClassifier#classifyAndResolve} to split them into a curated
 * {@code --module-path} and {@code -cp}, then forks a child JVM via
 * {@code -m rootModule/mainClass}.
 *
 * <p>This replaces the {@code exec-maven-plugin} + custom launcher pattern some JPMS builds use
 * for self-hosting. {@code exec-maven-plugin}'s {@code <classpath/>} placeholder dumps every
 * dependency onto a flat classpath, which cannot handle split packages. This goal does the
 * classification that {@code exec-maven-plugin} cannot.
 */
@Mojo(
    name = "exec",
    defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class ExecMojo extends AbstractMojo {

    /**
     * Source of jar candidates for the {@link #scope} parameter. Lowercase constant names so the
     * enum's {@code valueOf} binding accepts exactly the same command-line and POM values
     * ({@code compile}, {@code runtime}, {@code test}, {@code plugin}) that the parameter took
     * back when it was a plain {@code String} — an enum-typed {@code @Parameter} gets Maven to
     * reject an unknown value at binding time (before {@link #execute()} even runs) and documents
     * the valid set automatically in generated plugin docs ({@code mvn help:describe}).
     */
    public enum Scope {
        /**
         * The project's compile classpath.
         */
        compile,
        /**
         * The project's runtime classpath.
         */
        runtime,
        /**
         * The project's test classpath.
         */
        test,
        /**
         * The plugin's own class realm, for self-hosting builds where the target jars are loaded
         * as plugin dependencies rather than project dependencies.
         */
        plugin
    }

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor pluginDescriptor;

    /**
     * Fully-qualified main class name (e.g. {@code com.example.app.Main}).
     */
    @Parameter(property = "percolate.exec.mainClass", required = true)
    private String mainClass;

    /**
     * Root JPMS module name (e.g. {@code com.example.app}). Used both as the resolution
     * root for {@link ModuleGraphClassifier#classifyAndResolve} and as the module half of the
     * {@code -m rootModule/mainClass} argument.
     */
    @Parameter(property = "percolate.exec.rootModule", required = true)
    private String rootModule;

    /**
     * Source of jar candidates. See {@link Scope} for the valid values.
     */
    @Parameter(property = "percolate.exec.scope", defaultValue = "runtime")
    private Scope scope;

    /**
     * Optional {@code -Xmx} value, e.g. {@code 512m}. Omitted when blank.
     */
    @Parameter(property = "percolate.exec.maxHeap")
    private String maxHeap;

    /**
     * Extra JVM flags inserted before {@code --module-path} (e.g. {@code --enable-preview}).
     */
    @Parameter(property = "percolate.exec.additionalJvmArgs")
    private List<String> additionalJvmArgs;

    /**
     * Shell-word-tokenized (see {@link ParameterOverrides#tokenize}) JVM flags, split into tokens
     * and used <em>instead of</em> {@link #additionalJvmArgs} when non-blank.
     * <p>
     * {@link #additionalJvmArgs} is a {@code List<String>}, which Maven can only populate from
     * POM {@code <additionalJvmArg>} elements — there's no way to override it from the command
     * line. This property exists for exactly that case: overriding JVM flags ad-hoc as
     * {@code -Dpercolate.exec.jvmArgs="--enable-preview -ea"} for dev-loop use, without editing
     * the POM per invocation.
     * <p>
     * A flag value containing whitespace can be quoted, e.g.
     * {@code -Dpercolate.exec.jvmArgs="-Dmyapp.greeting='hello world'"}.
     * <p>
     * The property is the short {@code jvmArgs} rather than {@code additionalJvmArgs} because the
     * latter is already bound as the {@code List} parameter's own property.
     */
    @Parameter(property = "percolate.exec.jvmArgs")
    private String additionalJvmArgsOverride;

    /**
     * System property name prefixes to forward from the parent (outer {@code mvn}) JVM to the
     * forked process, as {@code -Dkey=value}. Empty by default: {@code mvn}'s own JVM carries
     * many built-in properties ({@code java.home}, {@code user.dir}, ...) that would be wrong or
     * redundant to hand to the child, and there's no reliable way to tell those apart from
     * properties the user actually passed with {@code -D}. Listing prefixes (e.g. {@code myapp.})
     * is how the caller opts specific ones in.
     */
    @Parameter(property = "percolate.exec.forwardSystemProperties")
    private List<String> forwardSystemProperties;

    /**
     * Arguments passed to {@code mainClass} after the {@code -m} flag.
     */
    @Parameter(property = "percolate.exec.arguments")
    private List<String> arguments;

    /**
     * Shell-word-tokenized (see {@link ParameterOverrides#tokenize}) arguments passed to
     * {@code mainClass}, split into tokens and used <em>instead of</em> {@link #arguments} when
     * non-blank.
     * <p>
     * {@link #arguments} is a {@code List<String>}, which Maven can only populate from POM
     * {@code <argument>} elements — there's no way to override it from the command line. This
     * property exists for exactly that case: a POM execution that hardcodes no {@code <arguments>}
     * of its own, invoked ad-hoc as {@code -Dpercolate.exec.args="task1 task2 --flag"} for
     * dev-loop use, without editing the POM per invocation.
     * <p>
     * An argument containing whitespace can be quoted, e.g.
     * {@code -Dpercolate.exec.args="build --message 'hello world'"}.
     * <p>
     * The property is the short {@code args} rather than {@code arguments} because the latter is
     * already bound as the {@code List} parameter's own property.
     */
    @Parameter(property = "percolate.exec.args")
    private String argumentsOverride;

    /**
     * Working directory for the forked process. Defaults to {@code ${project.basedir}}.
     */
    @Parameter(property = "percolate.exec.workingDirectory", defaultValue = "${project.basedir}", required = true)
    private File workingDirectory;

    /**
     * Skips execution of this goal when {@code true}.
     */
    @Parameter(property = "percolate.exec.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Maximum time to wait for the forked process to exit, in seconds. Omitted (or non-positive)
     * means wait indefinitely. On timeout the process is forcibly destroyed and the goal fails.
     */
    @Parameter(property = "percolate.exec.timeoutSeconds")
    private long timeoutSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("percolate.exec.skip=true, skipping execution");
            return;
        }

        if (mainClass == null || mainClass.isBlank()) {
            throw new MojoExecutionException("percolate.exec.mainClass must not be blank");
        }
        if (rootModule == null || rootModule.isBlank()) {
            throw new MojoExecutionException("percolate.exec.rootModule must not be blank");
        }
        if (!workingDirectory.isDirectory()) {
            throw new MojoExecutionException(
                "percolate.exec.workingDirectory does not exist or is not a directory: " + workingDirectory);
        }

        final List<Path> candidates;
        try {
            candidates = resolveCandidates();
        } catch (final DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve candidates: " + e.getMessage(), e);
        }

        getLog().info("classifying " + candidates.size() + " candidates (scope=" + scope + ")");

        final ModuleGraphClassifier.Classification classification =
            ModuleGraphClassifier.classifyAndResolve(
                candidates,
                Set.of(rootModule),
                rootModule,
                ModuleLayer.boot().configuration(),
                ModuleFinder.of(),
                msg -> getLog().info("[classify] " + msg));

        getLog().info("module-path=" + classification.modulePath().size()
            + " class-path=" + classification.classPath().size());

        final List<String> command = buildCommand(
            rootModule, mainClass, maxHeap, effectiveJvmArgs(), effectiveArguments(), classification);
        getLog().info("exec: " + String.join(" ", command));

        try {
            final Process process = new ProcessBuilder(command)
                .directory(workingDirectory)
                .inheritIO()
                .start();

            final int exitCode = awaitExit(process, timeoutSeconds, rootModule + "/" + mainClass);
            if (exitCode != 0) {
                throw new MojoFailureException(
                    rootModule + "/" + mainClass + " exited with code " + exitCode);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("exec failed: " + e.getMessage(), e);
        } catch (final IOException e) {
            throw new MojoExecutionException("exec failed: " + e.getMessage(), e);
        }
    }

    /**
     * The arguments passed to {@code mainClass}: the whitespace-split {@link #argumentsOverride}
     * when non-blank, else the POM {@link #arguments} list (possibly {@code null}).
     */
    List<String> effectiveArguments() {
        return ParameterOverrides.resolveEffective(argumentsOverride, arguments);
    }

    /**
     * The JVM flags for the forked process: {@link #additionalJvmArgsOverride} (or the POM
     * {@link #additionalJvmArgs} list) followed by the {@code -D} flags forwarded per
     * {@link #forwardSystemProperties}. Never {@code null}.
     */
    List<String> effectiveJvmArgs() {
        return combinedJvmArgs(additionalJvmArgsOverride, additionalJvmArgs,
            forwardedSystemPropertyArgs(System.getProperties(), forwardSystemProperties));
    }

    List<Path> resolveCandidates() throws DependencyResolutionRequiredException {
        return switch (scope) {
            case compile -> toPathList(project.getCompileClasspathElements());
            case runtime -> toPathList(project.getRuntimeClasspathElements());
            case test -> toPathList(project.getTestClasspathElements());
            case plugin -> pluginDescriptor.getArtifacts().stream()
                .map(Artifact::getFile)
                .filter(Objects::nonNull)
                .map(File::toPath)
                .toList();
        };
    }

    private static List<Path> toPathList(final List<String> elements) {
        return elements.stream().map(Path::of).toList();
    }

    /**
     * Builds {@code -Dkey=value} flags for every property in {@code properties} whose name starts
     * with one of {@code prefixes}. Returns an empty, deterministically-ordered list when
     * {@code prefixes} is null or holds no non-blank entry. Blank prefixes are ignored rather than
     * treated as a match-everything wildcard, which would forward {@code mvn}'s own built-in
     * properties.
     */
    static List<String> forwardedSystemPropertyArgs(final Properties properties, final List<String> prefixes) {
        if (prefixes == null) {
            return List.of();
        }
        final List<String> effectivePrefixes = prefixes.stream().filter(p -> !p.isBlank()).toList();
        if (effectivePrefixes.isEmpty()) {
            return List.of();
        }
        return properties.stringPropertyNames().stream()
            .filter(name -> effectivePrefixes.stream().anyMatch(name::startsWith))
            .sorted()
            .map(name -> "-D" + name + "=" + properties.getProperty(name))
            .toList();
    }

    /**
     * Waits for {@code process} to exit and returns its exit code. A non-positive
     * {@code timeoutSeconds} waits indefinitely; otherwise, on timeout the process and its
     * descendants are forcibly destroyed and a {@link MojoExecutionException} is thrown.
     * <p>
     * An interrupt while waiting also tears the process tree down before propagating, so a
     * cancelled build never orphans the fork regardless of which wait branch was taken.
     * <p>
     * A JVM shutdown hook backs up both of those paths: if the outer (mvn) JVM itself is killed
     * while we're waiting — e.g. a CI job cancellation signalling only the mvn PID — the hook
     * reaps the fork instead of leaving it orphaned. It's removed once we're done waiting so it
     * doesn't outlive this call.
     */
    static int awaitExit(final Process process, final long timeoutSeconds, final String label)
        throws InterruptedException, MojoExecutionException {
        final Thread killHook = new Thread(() -> destroyProcessTree(process));
        Runtime.getRuntime().addShutdownHook(killHook);
        try {
            try {
                if (timeoutSeconds <= 0) {
                    return process.waitFor();
                }
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    // The goal has already failed and inheritIO() leaves no streams for us to drain.
                    throw new MojoExecutionException(label + " timed out after " + timeoutSeconds + "s");
                }
                return process.exitValue();
            } catch (final MojoExecutionException | InterruptedException e) {
                destroyProcessTree(process);
                throw e;
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(killHook);
            } catch (final IllegalStateException ignored) {
                // JVM shutdown already started concurrently; the hook is about to run (or has
                // run) regardless, so there's nothing left to clean up.
            }
        }
    }

    /**
     * Fire-and-forget: the tree may take a moment to actually die. Kills descendants first, since
     * destroying only the direct child can orphan them.
     */
    private static void destroyProcessTree(final Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Resolves the JVM-arg override ({@code override}) against the POM list ({@code fromPom}) and
     * appends {@code forwardedProperties}, in that order. Never returns {@code null}; the result is
     * a fresh mutable list.
     */
    static List<String> combinedJvmArgs(final String override,
                                        final List<String> fromPom,
                                        final List<String> forwardedProperties) {
        final List<String> combined = new ArrayList<>(Objects.requireNonNullElse(
            ParameterOverrides.resolveEffective(override, fromPom), List.of()));
        combined.addAll(forwardedProperties);
        return combined;
    }

    static List<String> buildCommand(final String rootModule,
                                     final String mainClass,
                                     final String maxHeap,
                                     final List<String> additionalJvmArgs,
                                     final List<String> arguments,
                                     final ModuleGraphClassifier.Classification classification) {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());

        if (maxHeap != null && !maxHeap.isBlank()) {
            command.add("-Xmx" + maxHeap);
        }

        if (additionalJvmArgs != null) {
            command.addAll(additionalJvmArgs);
        }

        if (!classification.modulePath().isEmpty()) {
            command.add("--module-path");
            command.add(joinPaths(classification.modulePath()));
        }

        if (!classification.classPath().isEmpty()) {
            command.add("-cp");
            command.add(joinPaths(classification.classPath()));
        }

        command.add("-m");
        command.add(rootModule + "/" + mainClass);

        if (arguments != null) {
            command.addAll(arguments);
        }

        return command;
    }

    static String joinPaths(final List<Path> paths) {
        return paths.stream()
            .map(Path::toString)
            .collect(Collectors.joining(File.pathSeparator));
    }
}
