package build.percolate.maven.exec;

import build.percolate.core.ModuleGraphClassifier;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void effectiveJvmArgs_overridePresent_replacesPomList() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "additionalJvmArgs", List.of("-Xshare:off"));
        setField(mojo, "additionalJvmArgsOverride", "-ea --enable-preview");

        assertThat(mojo.effectiveJvmArgs()).containsExactly("-ea", "--enable-preview");
    }

    @Test
    void effectiveJvmArgs_noOverride_fallsBackToPomList() {
        final ExecMojo mojo = new ExecMojo();
        setField(mojo, "additionalJvmArgs", List.of("-Xshare:off"));

        assertThat(mojo.effectiveJvmArgs()).containsExactly("-Xshare:off");
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
