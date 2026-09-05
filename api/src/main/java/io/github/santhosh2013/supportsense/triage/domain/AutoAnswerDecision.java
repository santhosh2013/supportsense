package io.github.santhosh2013.supportsense.triage.domain;

import java.util.Optional;

/** Pure decision returned before any LLM invocation is considered. */
public record AutoAnswerDecision(boolean blocked, Optional<AutoAnswerBlockReason> reason) {

    public static AutoAnswerDecision allow() {
        return new AutoAnswerDecision(false, Optional.empty());
    }

    public static AutoAnswerDecision block(AutoAnswerBlockReason reason) {
        return new AutoAnswerDecision(true, Optional.of(reason));
    }
}