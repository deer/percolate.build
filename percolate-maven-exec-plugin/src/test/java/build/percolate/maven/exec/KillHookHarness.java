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

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Standalone process run by {@code ExecMojoTest} to exercise {@link ExecMojo#awaitExit}'s
 * shutdown hook: it forks a long-lived process, records that process's pid, then blocks in
 * {@code awaitExit} the same way {@code ExecMojo.execute} does. The test kills this harness with
 * SIGTERM (simulating an outer {@code mvn} JVM being cancelled) and checks that the forked process
 * dies with it, rather than being orphaned.
 */
public final class KillHookHarness {

    private KillHookHarness() {
    }

    public static void main(final String[] args) throws Exception {
        final Path pidFile = Path.of(args[0]);
        final Process sleeper = new ProcessBuilder("sleep", "60").start();
        Files.writeString(pidFile, Long.toString(sleeper.pid()));
        ExecMojo.awaitExit(sleeper, 0, "harness");
    }
}
