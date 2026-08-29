package build.percolate.maven.exec;

import build.percolate.core.ModuleGraphClassifier;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecMojoTest {

    private static final String JAVA_BIN =
        Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static final ModuleGraphClassifier.Classification EMPTY =
        new ModuleGraphClassifier.Classification(List.of(), List.of());

    @Test
    void buildCommand_minimal_hasNoOptionalFlags() {
        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", null, null, null, EMPTY);

        assertThat(command).containsExactly(JAVA_BIN, "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_maxHeap_addsXmxFlag() {
        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", "512m", null, null, EMPTY);

        assertThat(command).containsExactly(
            JAVA_BIN, "-Xmx512m", "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_blankMaxHeap_omitsXmxFlag() {
        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", "  ", null, null, EMPTY);

        assertThat(command).containsExactly(JAVA_BIN, "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_additionalJvmArgs_insertedBeforeModulePath() {
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar")), List.of());

        final List<String> command = ExecMojo.buildCommand(
            "root.module", "root.Main", null,
            List.of("--enable-preview", "-Dfoo=bar"), null, classification);

        assertThat(command).containsExactly(
            JAVA_BIN, "--enable-preview", "-Dfoo=bar",
            "--module-path", Path.of("/deps/a.jar").toString(),
            "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_modulePathOnly_addsModulePathFlag() {
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar"), Path.of("/deps/b.jar")), List.of());

        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", null, null, null, classification);

        assertThat(command).containsExactly(
            JAVA_BIN,
            "--module-path", ExecMojo.joinPaths(classification.modulePath()),
            "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_classPathOnly_addsCpFlag() {
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(), List.of(Path.of("/deps/legacy.jar")));

        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", null, null, null, classification);

        assertThat(command).containsExactly(
            JAVA_BIN,
            "-cp", Path.of("/deps/legacy.jar").toString(),
            "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_modulePathAndClassPath_bothFlagsPresentInOrder() {
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar")), List.of(Path.of("/deps/legacy.jar")));

        final List<String> command =
            ExecMojo.buildCommand("root.module", "root.Main", null, null, null, classification);

        assertThat(command).containsExactly(
            JAVA_BIN,
            "--module-path", Path.of("/deps/a.jar").toString(),
            "-cp", Path.of("/deps/legacy.jar").toString(),
            "-m", "root.module/root.Main");
    }

    @Test
    void buildCommand_arguments_appendedAfterModuleMainClass() {
        final List<String> command = ExecMojo.buildCommand(
            "root.module", "root.Main", null, null, List.of("--flag", "value"), EMPTY);

        assertThat(command).containsExactly(
            JAVA_BIN, "-m", "root.module/root.Main", "--flag", "value");
    }

    @Test
    void joinPaths_usesPlatformPathSeparator() {
        final String joined = ExecMojo.joinPaths(
            List.of(Path.of("/deps/a.jar"), Path.of("/deps/b.jar")));

        assertThat(joined).isEqualTo(
            Path.of("/deps/a.jar") + File.pathSeparator + Path.of("/deps/b.jar"));
    }

    @Test
    void execute_skipTrue_returnsWithoutTouchingProjectOrPlugin() {
        final ExecMojo mojo = new ExecMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "skip", true);

        // project/pluginDescriptor/mainClass/rootModule are all left null; skip must short-circuit
        // before any of them are touched, otherwise this throws NullPointerException.
        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    void resolveCandidates_unknownScope_throwsIllegalArgumentException() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "scope", "bogus");

        assertThatThrownBy(mojo::resolveCandidates)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bogus");
    }

    @Test
    void execute_blankMainClass_throwsMojoExecutionException() {
        final ExecMojo mojo = new ExecMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "mainClass", "  ");

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("mainClass");
    }

    @Test
    void execute_blankRootModule_throwsMojoExecutionException() {
        final ExecMojo mojo = new ExecMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "mainClass", "root.Main");
        setField(mojo, "rootModule", "");

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("rootModule");
    }

    @Test
    void execute_missingWorkingDirectory_throwsMojoExecutionException() {
        final ExecMojo mojo = new ExecMojo();
        mojo.setLog(new SystemStreamLog());
        setField(mojo, "mainClass", "root.Main");
        setField(mojo, "rootModule", "root.module");
        setField(mojo, "workingDirectory", new File("/no/such/directory/percolate-exec-test"));

        assertThatThrownBy(mojo::execute)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("workingDirectory");
    }

    @Test
    void forwardedSystemPropertyArgs_noPrefixes_returnsEmpty() {
        final Properties properties = new Properties();
        properties.setProperty("myapp.foo", "bar");

        assertThat(ExecMojo.forwardedSystemPropertyArgs(properties, null)).isEmpty();
        assertThat(ExecMojo.forwardedSystemPropertyArgs(properties, List.of())).isEmpty();
    }

    @Test
    void forwardedSystemPropertyArgs_blankPrefix_isIgnoredNotWildcard() {
        final Properties properties = new Properties();
        properties.setProperty("myapp.foo", "bar");
        properties.setProperty("java.home", "/opt/jdk");

        assertThat(ExecMojo.forwardedSystemPropertyArgs(properties, List.of("  "))).isEmpty();
        assertThat(ExecMojo.forwardedSystemPropertyArgs(properties, List.of("", "myapp.")))
            .containsExactly("-Dmyapp.foo=bar");
    }

    @Test
    void forwardedSystemPropertyArgs_matchingPrefix_isForwardedAndSorted() {
        final Properties properties = new Properties();
        properties.setProperty("myapp.b", "2");
        properties.setProperty("myapp.a", "1");
        properties.setProperty("java.home", "/opt/jdk");
        properties.setProperty("other.thing", "x");

        final List<String> result =
            ExecMojo.forwardedSystemPropertyArgs(properties, List.of("myapp."));

        assertThat(result).containsExactly("-Dmyapp.a=1", "-Dmyapp.b=2");
    }

    @Test
    void combinedJvmArgs_overridePresent_replacesPomListAndAppendsForwarded() {
        final List<String> result = ExecMojo.combinedJvmArgs(
            "-ea --enable-preview", List.of("-Xshare:off"), List.of("-Dmyapp.a=1"));

        assertThat(result).containsExactly("-ea", "--enable-preview", "-Dmyapp.a=1");
        assertThatCode(() -> result.add("-Xmx1g")).doesNotThrowAnyException();
    }

    @Test
    void combinedJvmArgs_noOverrideOrPomList_returnsForwardedOnly() {
        final List<String> result =
            ExecMojo.combinedJvmArgs(null, null, List.of("-Dmyapp.a=1"));

        assertThat(result).containsExactly("-Dmyapp.a=1");
    }

    @Test
    void awaitExit_completesBeforeTimeout_returnsExitCode() throws Exception {
        assertThat(ExecMojo.awaitExit(new ProcessBuilder("true").start(), 30, "x")).isZero();
        assertThat(ExecMojo.awaitExit(new ProcessBuilder("false").start(), 30, "x")).isEqualTo(1);
    }

    @Test
    void awaitExit_nonPositiveTimeout_waitsIndefinitely() throws Exception {
        assertThat(ExecMojo.awaitExit(new ProcessBuilder("true").start(), 0, "x")).isZero();
        assertThat(ExecMojo.awaitExit(new ProcessBuilder("true").start(), -1, "x")).isZero();
    }

    @Test
    void awaitExit_exceedsTimeout_throwsAndKillsProcess() throws Exception {
        final Process process = new ProcessBuilder("sleep", "30").start();

        assertThatThrownBy(() -> ExecMojo.awaitExit(process, 1, "root/Main"))
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("root/Main timed out after 1s");

        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void awaitExit_interruptedDuringIndefiniteWait_throwsAndKillsProcess() throws Exception {
        final Process process = new ProcessBuilder("sleep", "30").start();
        final Thread caller = Thread.currentThread();
        final Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ignored) {
                // fall through to interrupt the caller anyway
            }
            caller.interrupt();
        });
        interrupter.start();

        assertThatThrownBy(() -> ExecMojo.awaitExit(process, 0, "root/Main"))
            .isInstanceOf(InterruptedException.class);

        interrupter.join();
        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void effectiveArguments_overridePresent_replacesPomList() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "arguments", List.of("pom-arg"));
        setField(mojo, "argumentsOverride", "task1 task2 --flag");

        assertThat(mojo.effectiveArguments()).containsExactly("task1", "task2", "--flag");
    }

    @Test
    void effectiveArguments_noOverride_fallsBackToPomList() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "arguments", List.of("pom-arg"));

        assertThat(mojo.effectiveArguments()).containsExactly("pom-arg");
    }

    @Test
    void effectiveJvmArgs_overrideAndForwardedProperties_composeInOrder() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "additionalJvmArgs", List.of("-Xshare:off"));
        setField(mojo, "additionalJvmArgsOverride", "-ea --enable-preview");
        setField(mojo, "forwardSystemProperties", List.of("percolate.exec.test.forward."));
        System.setProperty("percolate.exec.test.forward.flag", "on");
        try {
            assertThat(mojo.effectiveJvmArgs())
                .containsExactly("-ea", "--enable-preview", "-Dpercolate.exec.test.forward.flag=on");
        } finally {
            System.clearProperty("percolate.exec.test.forward.flag");
        }
    }

    private static void setField(final Object target, final String name, final Object value) {
        try {
            final Field field = ExecMojo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
