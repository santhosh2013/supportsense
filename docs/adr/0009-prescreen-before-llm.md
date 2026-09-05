# ADR-0009: Deterministic pre-screen before the LLM

**Status:** Accepted

## Context

FR-6 requires abstention on sensitive topics (billing, refund, legal). The obvious
implementation is a `Category.auto_answer_blocked` flag keyed off the LLM's *predicted*
category. That gate has a hole: a billing ticket the model misclassifies as "technical"
sails straight past it — the guard fails exactly when the model is wrong, which is the only
time it matters.

## Decision

Run a deterministic, keyword/regex pre-screen over the **raw** ticket `subject + body`,
**before and independently of** the LLM classification call. Terms are configuration
(`supportsense.prescreen.terms`), matched **case-insensitively with word boundaries**, never
as a substring. If either this gate or the category flag trips, auto-answer is blocked.

## Consequences

Two independent gates catch cases where one alone would fail: the pre-screen does not
depend on classification being correct, and the category flag catches topics the keyword
list does not anticipate. Word-boundary matching avoids the false positive of "sue" matching
inside "issue". The cost is that the term list requires ongoing curation as new sensitive
phrasing is observed — accepted, since it is a config change, not a code change.
