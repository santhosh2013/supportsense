package io.github.santhosh2013.supportsense.triage.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure, configuration-driven pre-screen for sensitive ticket content. */
public final class PreScreenMatcher {

    private final List<CompiledTerm> compiledTerms;

    public PreScreenMatcher(List<String> configuredTerms) {
        Objects.requireNonNull(configuredTerms, "configuredTerms must not be null");
        if (configuredTerms.isEmpty()) {
            throw new IllegalArgumentException("configuredTerms must not be empty");
        }
        this.compiledTerms = configuredTerms.stream()
                .map(PreScreenMatcher::compile)
                .sorted(Comparator.comparingInt((CompiledTerm term) -> term.value().length()).reversed())
                .toList();
    }

    public PreScreenMatch match(String text) {
        if (text == null || text.isBlank()) {
            return PreScreenMatch.noMatch();
        }

        return compiledTerms.stream()
                .filter(term -> term.pattern().matcher(text).find())
                .findFirst()
                .map(term -> PreScreenMatch.match(term.value()))
                .orElseGet(PreScreenMatch::noMatch);
    }

    List<Pattern> compiledPatterns() {
        return compiledTerms.stream().map(CompiledTerm::pattern).toList();
    }

    private static CompiledTerm compile(String configuredTerm) {
        String term = Objects.requireNonNull(configuredTerm, "configured term must not be null").trim();
        if (term.isEmpty()) {
            throw new IllegalArgumentException("configured term must not be blank");
        }

        String phrasePattern = List.of(term.split("\\s+", -1)).stream()
                .map(Pattern::quote)
                .reduce((left, right) -> left + "\\s+" + right)
                .orElseThrow();
        String boundaryPattern = "(?iu)(?<![\\p{L}\\p{N}_])" + phrasePattern + "(?![\\p{L}\\p{N}_])";
        return new CompiledTerm(term, Pattern.compile(boundaryPattern));
    }

    private record CompiledTerm(String value, Pattern pattern) {}
}