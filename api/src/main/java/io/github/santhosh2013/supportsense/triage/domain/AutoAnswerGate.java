package io.github.santhosh2013.supportsense.triage.domain;

import java.util.Objects;

/** Combines deterministic pre-screen and category policy as an OR gate. */
public final class AutoAnswerGate {

    private final PreScreenMatcher preScreenMatcher;

    public AutoAnswerGate(PreScreenMatcher preScreenMatcher) {
        this.preScreenMatcher = Objects.requireNonNull(preScreenMatcher, "preScreenMatcher must not be null");
    }

    public AutoAnswerDecision evaluate(String ticketText, boolean categoryAutoAnswerBlocked) {
        if (preScreenMatcher.match(ticketText).matched()) {
            return AutoAnswerDecision.block(AutoAnswerBlockReason.KEYWORD_PRESCREEN);
        }
        if (categoryAutoAnswerBlocked) {
            return AutoAnswerDecision.block(AutoAnswerBlockReason.SENSITIVE_CATEGORY);
        }
        return AutoAnswerDecision.allow();
    }

    /** Executes downstream work only after both deterministic safety gates allow it. */
    public <T> T continueOnlyWhenAllowed(
            String ticketText, boolean categoryAutoAnswerBlocked, T blockedValue, TriageContinuation<T> continuation) {
        if (evaluate(ticketText, categoryAutoAnswerBlocked).blocked()) {
            return blockedValue;
        }
        return continuation.continueTriage();
    }
}