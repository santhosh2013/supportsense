package io.github.santhosh2013.supportsense.triage.domain;

import java.util.Optional;

/** Result of screening raw ticket text against configured sensitive terms. */
public record PreScreenMatch(boolean matched, Optional<String> term) {

    public static PreScreenMatch noMatch() {
        return new PreScreenMatch(false, Optional.empty());
    }

    public static PreScreenMatch match(String term) {
        return new PreScreenMatch(true, Optional.of(term));
    }
}