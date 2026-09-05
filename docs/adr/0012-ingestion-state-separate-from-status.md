# ADR-0012: `ingestion_state` separate from business `status`

**Status:** Accepted

## Context

Persist-first ingestion (ADR-0011) needs a `PROCESSING` state to represent "a worker has
claimed this row." `Ticket.status` already exists and drives BR-A09's lifecycle:
`NEW, TRIAGED, IN_PROGRESS, PENDING_CUSTOMER, RESOLVED, CLOSED, DUPLICATE`. Adding
`PROCESSING` to that enum was considered.

## Decision

Add a **second, orthogonal** column, `ingestion_state`, with its own small enum:
`PENDING, PROCESSING, DONE, FAILED`. `Ticket.status` is untouched.

## Consequences

`PROCESSING` is a pipeline state describing whether the async classification worker has
claimed the row — it has nothing to do with the ticket's business lifecycle. Merging the two
would mean every legal/illegal transition test for BR-A09 would need to account for a state
that is not part of that rule, and a ticket could plausibly need to be simultaneously
"business-status NEW" and "pipeline-status PROCESSING," which a single enum cannot represent
cleanly. Two columns cost one extra field but keep BR-A09's transition machine exactly as
specified.
