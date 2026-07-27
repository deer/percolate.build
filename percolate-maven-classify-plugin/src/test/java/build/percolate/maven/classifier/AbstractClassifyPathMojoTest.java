package build.percolate.maven.classifier;

import build.percolate.core.ModuleGraphClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractClassifyPathMojoTest {

    @TempDir
    private Path tempDir;

    @Test
    void writeArgfile_modulePathOnly_noSeed_writesTwoLines() throws IOException {
        final Path argfile = tempDir.resolve("out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar")), List.of());

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, Set.of());

        assertThat(Files.readString(argfile)).isEqualTo(
            "--module-path\n"
                + "\"" + Path.of("/deps/a.jar") + "\"\n");
    }

    @Test
    void writeArgfile_modulePathWithSeed_addsAddModulesLine() throws IOException {
        final Path argfile = tempDir.resolve("out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar")), List.of());
        final Set<String> seed = new LinkedHashSet<>(List.of("mod.a", "mod.b"));

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, seed);

        assertThat(Files.readString(argfile)).isEqualTo(
            "--module-path\n"
                + "\"" + Path.of("/deps/a.jar") + "\"\n"
                + "--add-modules=mod.a,mod.b\n");
    }

    @Test
    void writeArgfile_classPathOnly_writesClassPathLines() throws IOException {
        final Path argfile = tempDir.resolve("out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(), List.of(Path.of("/deps/legacy.jar")));

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, Set.of());

        assertThat(Files.readString(argfile)).isEqualTo(
            "--class-path\n"
                + "\"" + Path.of("/deps/legacy.jar") + "\"\n");
    }

    @Test
    void writeArgfile_modulePathAndClassPath_writesBothSections() throws IOException {
        final Path argfile = tempDir.resolve("out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(Path.of("/deps/a.jar")), List.of(Path.of("/deps/legacy.jar")));

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, Set.of());

        assertThat(Files.readString(argfile)).isEqualTo(
            "--module-path\n"
                + "\"" + Path.of("/deps/a.jar") + "\"\n"
                + "--class-path\n"
                + "\"" + Path.of("/deps/legacy.jar") + "\"\n");
    }

    @Test
    void writeArgfile_empty_writesEmptyFile() throws IOException {
        final Path argfile = tempDir.resolve("out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(), List.of());

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, Set.of());

        assertThat(Files.readString(argfile)).isEmpty();
    }

    @Test
    void writeArgfile_createsMissingParentDirectories() throws IOException {
        final Path argfile = tempDir.resolve("nested/dir/out.args");
        final ModuleGraphClassifier.Classification classification = new ModuleGraphClassifier.Classification(
            List.of(), List.of(Path.of("/deps/legacy.jar")));

        AbstractClassifyPathMojo.writeArgfile(argfile, classification, Set.of());

        assertThat(argfile).exists();
    }

    @Test
    void join_usesPlatformPathSeparator() {
        final String joined = AbstractClassifyPathMojo.join(
            List.of(Path.of("/deps/a.jar"), Path.of("/deps/b.jar")));

        assertThat(joined).isEqualTo(
            Path.of("/deps/a.jar") + File.pathSeparator + Path.of("/deps/b.jar"));
    }
}
