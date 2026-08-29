package build.percolate.core;

/*-
 * #%L
 * Percolate Core
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

import java.util.List;

/**
 * Helpers for reconciling a whitespace-separated {@code -D} string override against a
 * POM-configured {@code List<String>} parameter. Maven can only populate a {@code List<String>}
 * from repeated POM elements, never from the command line, so plugins that want an ad-hoc
 * command-line override pair the list with a sibling {@code String} property and resolve the two
 * here.
 */
public final class ParameterOverrides {

    private ParameterOverrides() {
    }

    /**
     * Resolves {@code raw} (a whitespace-separated {@code -D} override) against {@code fallback}
     * (the POM-configured {@code List<String>}): returns the tokenized {@code raw} when non-blank,
     * otherwise {@code fallback} unchanged (which may be {@code null}).
     * <p>
     * Tokenization is a plain split on runs of whitespace, so an individual token cannot itself
     * contain whitespace (e.g. a path with a space). Callers that need such values must configure
     * them in the POM list rather than via the command-line override.
     */
    public static List<String> resolveEffective(final String raw, final List<String> fallback) {
        return raw != null && !raw.isBlank() ? List.of(raw.trim().split("\\s+")) : fallback;
    }
}
