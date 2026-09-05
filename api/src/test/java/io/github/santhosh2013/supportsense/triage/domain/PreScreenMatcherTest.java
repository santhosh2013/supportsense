package io.github.santhosh2013.supportsense.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PreScreenMatcherTest {

    private final PreScreenMatcher matcher = new PreScreenMatcher(
            List.of("sue", "class-action", "billing dispute", "lawyer"));

    @Test
    void substringInsideWordNeverMatches() {
        assertThat(matcher.match("issue").matched()).isFalse();
        assertThat(matcher.match("pursue").matched()).isFalse();
        assertThat(matcher.match("tissue").matched()).isFalse();
    }

    @Test
    void standaloneTermMatchesAcrossPunctuationBoundaries() {
        for (String text : List.of("sue.", "(sue)", "\"sue\"", "sue,")) {
            assertThat(matcher.match(text).matched()).as(text).isTrue();
        }
    }

    @Test
    void matchingIsCaseInsensitiveAndUnicodeSafe() {
        assertThat(matcher.match("SUE").matched()).isTrue();
        assertThat(matcher.match("Sue").matched()).isTrue();
        assertThatCode(() -> matcher.match("café résumé")).doesNotThrowAnyException();
    }

    @Test
    void possessiveMatchesConfiguredWord() {
        assertThat(matcher.match("The lawyer's response").matched()).isTrue();
    }

    @Test
    void configuredHyphenatedPhraseMatches() {
        assertThat(matcher.match("This is a class-action claim").matched()).isTrue();
    }

    @Test
    void configuredLawsuitMatchesAsAWholeWord() {
        PreScreenMatcher lawsuitMatcher = new PreScreenMatcher(List.of("lawsuit"));

        assertThat(lawsuitMatcher.match("I will file a lawsuit").matched()).isTrue();
        assertThat(lawsuitMatcher.match("lawsuit-related").matched()).isTrue();
    }

    @Test
    void multiWordPhraseAllowsVariableWhitespaceAndNewlines() {
        assertThat(matcher.match("There is a billing    dispute").matched()).isTrue();
        assertThat(matcher.match("There is a billing\n\tdispute").matched()).isTrue();
    }

    @Test
    void nullEmptyAndWhitespaceAreSafeNoMatches() {
        assertThat(matcher.match(null).matched()).isFalse();
        assertThat(matcher.match("").matched()).isFalse();
        assertThat(matcher.match("   ").matched()).isFalse();
    }

    @Test
    void emptyOrMalformedConfigurationFailsFast() {
        assertThatThrownBy(() -> new PreScreenMatcher(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> new PreScreenMatcher(List.of("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void fiftyKilobyteBodyIsBoundedAndSafe() {
        String body = "safe text ".repeat(5_120);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThat(matcher.match(body).matched()).isFalse();
        });
    }

    @Test
    void patternsArePrecompiledOnceAndReusedAcrossInvocations() {
        List<Pattern> before = matcher.compiledPatterns();
        matcher.match("sue");
        matcher.match("no match");
        List<Pattern> after = matcher.compiledPatterns();

        assertThat(after).containsExactlyElementsOf(before);
        for (int index = 0; index < before.size(); index++) {
            assertThat(after.get(index)).isSameAs(before.get(index));
        }
    }
}