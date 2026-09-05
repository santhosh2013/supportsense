# Metrics — SupportSense

Written before A6 so the implementation cannot drift from the claim. If a number cannot be
produced with a screenshot or a query, it does not go on the resume.

## False-deflection rate

The headline claim is **deflection rate paired with false-deflection rate — never
deflection alone.**

```
false-deflection rate =
    (tickets WHERE auto_answered = true
       AND a reopen event occurred within 48h of first_resolved_at)
    ÷
    (tickets WHERE auto_answered = true)
```

- **`auto_answered`** (on `tickets`) is set only when the system resolved the ticket without
  a human edit — this is what makes it possible to separate auto-answered tickets from
  human-resolved ones at all.
- The 48-hour window is measured from **`first_resolved_at`**, not `created_at` and not the
  most recent resolution — a ticket resolved, reopened, and resolved again should still be
  measured from its first resolution.
- A **reopen** is a `ticket_events` row with `from_status IN ('RESOLVED','CLOSED')` and
  `to_status NOT IN ('RESOLVED','CLOSED')`. `RESOLVED → CLOSED` is a normal forward closure
  and must **not** be counted as a reopen — counting it would silently inflate the
  false-deflection rate, which is the one number here that most needs to be honest.

## Deflection rate

```
deflection rate = (tickets resolved with resolved_by IN ('AI_ACCEPTED','AI_AUTO'))
                   ÷ (all resolved tickets)
```

Reported from `/api/analytics/deflection` (A6).

## Other metrics (measured from A2 onward)

| Metric | Source | Note |
|---|---|---|
| Macro-F1 (classification) | Confusion-matrix endpoint | Report per-class, not raw accuracy |
| Confidence calibration, threshold T/C | Calibration plot + sweep table | Chosen from data, not guessed |
| Abstention accuracy (false-refusal / false-answer) | Eval run | Report both — over-abstaining is also a failure |
| precision@5 before/after re-ranking | Eval runs | Two numbers, not one |
| Groundedness | `FactCheckingEvaluator` | Target ≥ 0.90 |
| Rs/ticket before/after caching | `triage_results` + `suggestions` | Report both |
| Prompt-injection attacks blocked | `notes/ai/guardrails.md` | Target 5/5 |
