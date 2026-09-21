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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
     * Resolves {@code raw} (a shell-word-tokenized {@code -D} override) against {@code fallback}
     * (the POM-configured {@code List<String>}): returns the tokenized {@code raw} when non-blank,
     * otherwise {@code fallback} unchanged (which may be {@code null}).
     *
     * @see #tokenize for the quoting rules a token may use to contain whitespace
     */
    public static List<String> resolveEffective(final String raw, final List<String> fallback) {
        return raw != null && !raw.isBlank() ? tokenize(raw) : fallback;
    }

    /**
     * Splits {@code raw} into shell-like words: runs of unquoted whitespace separate tokens, a
     * single-quoted span is taken literally (no escapes recognised inside it), and a
     * double-quoted span recognises {@code \"}, {@code \\}, and {@code \$} as escapes for those
     * three characters while passing every other backslash through unchanged. Outside quotes, a
     * backslash escapes the following character (letting a literal space, quote, or backslash
     * appear in an otherwise-unquoted token). This is the same core word-splitting behavior as
     * {@code sh}, without variable expansion or command substitution, which is enough to let a
     * command-line override express an argument containing spaces (e.g.
     * {@code -Dpercolate.exec.args="build --message 'hello world'"}) — the whitespace-split
     * tokenizer this replaces could not. Note that an empty quoted span (e.g. {@code ''} or
     * {@code ""}) produces an empty-string token, same as in {@code sh}.
     *
     * @throws IllegalArgumentException if a quote is left unterminated
     */
    public static List<String> tokenize(final String raw) {
        Objects.requireNonNull(raw, "raw");
        final List<String> tokens = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0; // 0 = unquoted, otherwise the active quote character
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\') {
                    if (i + 1 >= raw.length()) {
                        throw new IllegalArgumentException(
                            "Unterminated escape at end of override: " + raw);
                    }
                    if (isDoubleQuoteEscapable(raw.charAt(i + 1))) {
                        current.append(raw.charAt(++i));
                    } else {
                        current.append(c);
                    }
                } else {
                    current.append(c);
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> {
                    quote = c;
                    inToken = true;
                }
                case '\\' -> {
                    if (i + 1 >= raw.length()) {
                        throw new IllegalArgumentException(
                            "Unterminated escape at end of override: " + raw);
                    }
                    current.append(raw.charAt(++i));
                    inToken = true;
                }
                default -> {
                    current.append(c);
                    inToken = true;
                }
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unterminated " + quote + " quote in override: " + raw);
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }

    private static boolean isDoubleQuoteEscapable(final char c) {
        return c == '"' || c == '\\' || c == '$';
    }
}
