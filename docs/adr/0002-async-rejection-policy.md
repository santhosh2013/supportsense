# ADR-0002: `AbortPolicy` over `CallerRunsPolicy`

**Status:** Superseded by [ADR-0015](0015-rejection-returns-202.md)

## Context

BR-A01 requires `POST /api/tickets` to return 202 in under 400ms. The bounded ingestion
executor (core 4, max 8, queue 500) must have a rejection policy for when the queue fills.

## Decision

Use `ThreadPoolExecutor.AbortPolicy`, never `CallerRunsPolicy`.

## Consequences

`CallerRunsPolicy` makes the **request thread** execute the ingestion once the queue is
full. That directly violates BR-A01's sub-400ms contract — under load, the endpoint would
silently begin blocking for seconds, converting a capacity problem into a latency problem
that is invisible until a panel or a customer notices it. `AbortPolicy` refuses to let the
request thread execute the work, which is the property that actually matters here — not the
specific status code returned on rejection.

## Note on supersession

This ADR originally also specified that rejection should return **503 + `Retry-After`**.
That part is superseded by ADR-0015: once ingestion became persist-first, the ticket is
already durably stored before the executor is touched, so 503 would misreport a successful,
recoverable outcome as a failure. The `AbortPolicy` choice itself is unchanged and remains
correct.
