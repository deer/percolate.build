package build.percolate.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
