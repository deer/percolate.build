# Percolate

A classification algorithm for modulepath/classpath decisions, and a pair of Maven plugins
built on top of it.

Given a flat list of candidate jars, Percolate decides which belong on `--module-path` and
which belong on `-classpath`: version dedupe (newest wins), subset dedupe (a jar whose
packages are a strict subset of another's is superseded), and a tiered split-package
resolution policy for genuine conflicts. See the [`ModuleGraphClassifier`][classifier]
javadoc for the full algorithm.

[classifier]: percolate-core/src/main/java/build/percolate/core/ModuleGraphClassifier.java

## Modules

| Module | Artifact | What it does |
|---|---|---|
| [`percolate-core`](percolate-core) | `build.percolate:percolate-core` | The classifier itself — no Maven dependency, plain Java library. |
| [`percolate-maven-classify-plugin`](percolate-maven-classify-plugin) | `build.percolate:percolate-maven-classify-plugin` | Read-only classification: writes a `--module-path` / `-classpath` argfile for `maven-compiler-plugin` and `maven-surefire-plugin` to consume. |
| [`percolate-maven-exec-plugin`](percolate-maven-exec-plugin) | `build.percolate:percolate-maven-exec-plugin` | Classifies a project's dependencies, then forks a child JVM with `-m rootModule/mainClass` instead of a flat classpath — a JPMS-aware replacement for `exec-maven-plugin`. |

## Usage

### Classify plugin

Binds to `generate-sources` by default and writes an `@argfile`:

```xml
<plugin>
    <groupId>build.percolate</groupId>
    <artifactId>percolate-maven-classify-plugin</artifactId>
    <version>${percolate.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>classify-compile-path</goal>
                <goal>classify-test-compile-path</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Each goal exposes `<prefix>.modulepath` / `<prefix>.classpath` project properties
(`percolate.classify.compile.*` and `percolate.classify.test.*`) and writes an argfile under
`target/` that `maven-compiler-plugin` can consume via
`<compilerArgument>@${project.build.directory}/percolate-classify-compile.args</compilerArgument>`
(with `<useModulePath>false</useModulePath>` so the two classifications aren't computed twice).

### Exec plugin

```xml
<plugin>
    <groupId>build.percolate</groupId>
    <artifactId>percolate-maven-exec-plugin</artifactId>
    <version>${percolate.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>exec</goal>
            </goals>
            <configuration>
                <rootModule>com.example.app</rootModule>
                <mainClass>com.example.app.Main</mainClass>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Configurable via `percolate.exec.mainClass`, `percolate.exec.rootModule`,
`percolate.exec.scope` (`compile` / `runtime` / `test` / `plugin`), `percolate.exec.maxHeap`,
`percolate.exec.additionalJvmArgs`, `percolate.exec.arguments`, and
`percolate.exec.workingDirectory`.

## Building

Requires JDK 25 and Maven 3.9.16+ (or use the bundled wrapper).

```
./mvnw clean install
```

## License

Apache License, Version 2.0 — see [LICENSE](LICENSE).
