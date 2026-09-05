# ADR-0011: Persist-first ingestion with sweep and reaper

**Status:** Accepted

## Context

BR-A01 requires `POST /api/tickets` to dispatch classification asynchronously. A naive
in-memory `@Async` queue loses all queued work on a pod restart — and Cloud Run recycles
instances routinely, so this is not a hypothetical edge case.

## Decision

The ticket row **is** the work item; there is no separate job/outbox table. The row is
committed (`status=NEW`, `ingestion_state=PENDING`) before the executor is touched, and
dispatch fires from `@TransactionalEventListener(phase = AFTER_COMMIT)` so the worker never
starts before the row is visible to other transactions.

A scheduled **sweep** (every 60s, plus once at startup) claims and dispatches any
`PENDING` row via a conditional update:

```sql
UPDATE tickets SET ingestion_state='PROCESSING', claimed_at=:now, attempt_count=attempt_count+1
 WHERE id=:id AND ingestion_state='PENDING';
```

Proceeding only when exactly one row is affected makes this safe across multiple concurrent
Cloud Run instances with **no distributed lock**.

A scheduled **reaper** (every 60s) resets rows stuck in `PROCESSING` for more than 15
minutes back to `PENDING`, and marks them `FAILED` (with the error persisted) once
`attempt_count` reaches 3 — stopping a poison ticket from retrying forever.

## Consequences

Ingestion survives a restart with no additional table: the sweep simply re-discovers
`PENDING` rows. The cost is the claim/sweep/reaper machinery itself, and a small latency
tail for tickets recovered by the sweep rather than dispatched immediately (bounded by the
60s sweep interval). This decision also motivated ADR-0015: because the ticket is durably
committed before dispatch, an executor rejection is no longer data loss.
