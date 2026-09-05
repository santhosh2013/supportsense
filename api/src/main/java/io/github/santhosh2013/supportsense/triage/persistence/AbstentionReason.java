package io.github.santhosh2013.supportsense.triage.persistence;

/**
 * Records WHICH gate fired, not just that abstention occurred — see FR-6 / ADR-0009. This
 * is the analytics view A6 relies on.
 */
public enum AbstentionReason {
    NONE,
    LOW_CONFIDENCE,
    LOW_SIMILARITY,
    ENTERPRISE_TIER,
    SENSITIVE_CATEGORY,
    KEYWORD_PRESCREEN,
    TOOL_FAILURE
}
