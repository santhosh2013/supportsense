# ADR-0015: Rejection returns 202 + counter

**Status:** Accepted — supersedes [ADR-0002](0002-async-rejection-policy.md)'s response-code choice

## Context

ADR-0002 specified that an executor rejection (`AbortPolicy` firing on a full queue) should
return 503 + `Retry-After`. That decision predates persist-first ingestion (ADR-0011). Once
the ticket row is committed **before** the executor is touched, the premise behind 503 no
longer holds.

## Decision

On `RejectedExecutionException`, return **202** (identical to the success path), increment
the `ingestion.queue.rejected` counter, and leave `ingestion_state = PENDING`. The scheduled
sweep collects the row within 60 seconds. `AbortPolicy` itself is unchanged — the objective
was always to prevent the request thread from executing the work, not to produce a
particular status code.

## Consequences

Returning 503 for a ticket that is already durably persisted would misreport the outcome to
the caller and invite unnecessary retries for work the system is already going to complete.
202 is honest: the request was accepted, and it will be processed, just slightly later than
the immediate-dispatch path. The trade-off is that a caller cannot distinguish "dispatched
immediately" from "picked up by the sweep in up to 60 seconds" from the response alone —
acceptable, since BR-A01's guarantee is about response latency, not processing latency.

This is a genuine reversal: a later design change (persist-first ingestion) invalidated an
earlier decision's premise (that rejection meant lost work), and the response contract was
corrected to match. ADR-0002 is retained, marked superseded, rather than deleted, so this
reasoning trail survives.
