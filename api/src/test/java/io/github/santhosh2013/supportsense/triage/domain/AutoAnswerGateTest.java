package io.github.santhosh2013.supportsense.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoAnswerGateTest {

    private final AutoAnswerGate gate = new AutoAnswerGate(new PreScreenMatcher(List.of("refund")));

    @Test
    void noKeywordAndSafeCategoryAllowsAutoAnswer() {
        assertThat(gate.evaluate("How do I reset my password?", false).blocked()).isFalse();
    }

    @Test
    void keywordWithSafeCategoryBlocksAsKeywordPrescreen() {
        AutoAnswerDecision decision = gate.evaluate("I need a refund", false);
        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains(AutoAnswerBlockReason.KEYWORD_PRESCREEN);
    }

    @Test
    void safeTextWithBlockedCategoryBlocksAsSensitiveCategory() {
        AutoAnswerDecision decision = gate.evaluate("Please help", true);
        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains(AutoAnswerBlockReason.SENSITIVE_CATEGORY);
    }

    @Test
    void keywordAndBlockedCategoryStillBlocksViaIndependentPrescreen() {
        AutoAnswerDecision decision = gate.evaluate("Refund needed", true);
        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains(AutoAnswerBlockReason.KEYWORD_PRESCREEN);
    }

    @Test
    void prescreenBlocksBeforeAnyDownstreamContinuationCanRun() {
        String result = gate.continueOnlyWhenAllowed(
                "I need a refund",
                false,
                "ROUTE_TO_HUMAN",
                () -> {
                    throw new AssertionError("The LLM continuation must not be called for a pre-screen match");
                });

        assertThat(result).isEqualTo("ROUTE_TO_HUMAN");
    }

    @Test
    void safeInputReachesTheDownstreamContinuation() {
        String result = gate.continueOnlyWhenAllowed(
                "How do I reset my password?", false, "ROUTE_TO_HUMAN", () -> "CALL_LLM_IN_A2");

        assertThat(result).isEqualTo("CALL_LLM_IN_A2");
    }
}