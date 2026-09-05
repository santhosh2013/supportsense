package io.github.santhosh2013.supportsense.triage.domain;

/** Maps to the persisted abstention-reason vocabulary when A2 writes TriageResult rows. */
public enum AutoAnswerBlockReason {
    KEYWORD_PRESCREEN,
    SENSITIVE_CATEGORY
}