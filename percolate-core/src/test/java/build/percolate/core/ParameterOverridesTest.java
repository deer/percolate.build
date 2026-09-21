package build.percolate.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterOverridesTest {

    @Test
    void resolveEffective_blankRaw_returnsFallback() {
        final List<String> fallback = List.of("from-pom");

        assertThat(ParameterOverrides.resolveEffective(null, fallback)).isSameAs(fallback);
        assertThat(ParameterOverrides.resolveEffective("", fallback)).isSameAs(fallback);
        assertThat(ParameterOverrides.resolveEffective("   ", fallback)).isSameAs(fallback);
    }

    @Test
    void resolveEffective_blankRaw_nullFallback_returnsNull() {
        assertThat(ParameterOverrides.resolveEffective(null, null)).isNull();
    }

    @Test
    void resolveEffective_nonBlankRaw_splitsOnWhitespaceAndOverridesFallback() {
        assertThat(ParameterOverrides.resolveEffective("  a.b c.d   e.f  ", List.of("from-pom")))
            .containsExactly("a.b", "c.d", "e.f");
    }

    @Test
    void resolveEffective_singleToken_returnsSingletonList() {
        assertThat(ParameterOverrides.resolveEffective("  only.one  ", null))
            .containsExactly("only.one");
    }

    @Test
    void tokenize_singleQuotedSpan_isLiteral() {
        assertThat(ParameterOverrides.tokenize("build --message 'hello world'"))
            .containsExactly("build", "--message", "hello world");
    }

    @Test
    void tokenize_singleQuotedSpan_ignoresEscapes() {
        assertThat(ParameterOverrides.tokenize("'a\\b'")).containsExactly("a\\b");
    }

    @Test
    void tokenize_doubleQuotedSpan_allowsWhitespace() {
        assertThat(ParameterOverrides.tokenize("say \"hello world\""))
            .containsExactly("say", "hello world");
    }

    @Test
    void tokenize_doubleQuotedSpan_recognisesBackslashEscapes() {
        assertThat(ParameterOverrides.tokenize("\"a\\\"b\\\\c\\$d\""))
            .containsExactly("a\"b\\c$d");
    }

    @Test
    void tokenize_doubleQuotedSpan_passesUnrecognisedBackslashThrough() {
        assertThat(ParameterOverrides.tokenize("\"C:\\Users\\name\""))
            .containsExactly("C:\\Users\\name");
    }

    @Test
    void tokenize_unquotedBackslash_escapesNextCharacter() {
        assertThat(ParameterOverrides.tokenize("hello\\ world")).containsExactly("hello world");
    }

    @Test
    void tokenize_adjacentQuotedSpans_concatenateIntoOneToken() {
        assertThat(ParameterOverrides.tokenize("foo'bar'\"baz\"")).containsExactly("foobarbaz");
    }

    @Test
    void tokenize_emptyQuotedSpan_producesEmptyToken() {
        assertThat(ParameterOverrides.tokenize("''")).containsExactly("");
    }

    @Test
    void tokenize_unterminatedSingleQuote_throws() {
        assertThatThrownBy(() -> ParameterOverrides.tokenize("build 'unterminated"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenize_unterminatedDoubleQuote_throws() {
        assertThatThrownBy(() -> ParameterOverrides.tokenize("build \"unterminated"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenize_trailingBackslash_throws() {
        assertThatThrownBy(() -> ParameterOverrides.tokenize("build \\"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenize_plainWhitespaceSeparated_matchesOldBehavior() {
        assertThat(ParameterOverrides.tokenize("  a.b c.d   e.f  "))
            .containsExactly("a.b", "c.d", "e.f");
    }

    @Test
    void tokenize_null_throwsNullPointerException() {
        assertThatThrownBy(() -> ParameterOverrides.tokenize(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tokenize_trailingBackslashInDoubleQuotes_throwsUnterminatedEscape() {
        assertThatThrownBy(() -> ParameterOverrides.tokenize("\"build\\"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unterminated escape");
    }
}
