# ADR-0004: Database-enforced invariants over application checks

**Status:** Accepted

## Context

Several invariants are candidates for either an application-level check (a `SELECT` before
an `INSERT`, or a Bean Validation annotation) or a database constraint: idempotent ingestion
by `external_ref`, confidence values as valid probabilities, and canonical ordering of
duplicate-ticket pairs.

## Decision

Enforce these at the database level:

- `ux_ticket_external_ref UNIQUE (external_ref)` — not a read-then-write check
- `numeric(4,3)` + `CHECK (x BETWEEN 0 AND 1)` for every confidence column — not a
  floating-point type with application-level range validation
- `CHECK (ticket_a_id < ticket_b_id)` + `UNIQUE (ticket_a_id, ticket_b_id)` for duplicate
  pairs — not an application-level ordering convention

## Consequences

A constraint holds under concurrency, under retries, and for any future caller that bypasses
the service layer entirely (a batch job, a direct SQL client, a different microservice). An
application-level check only holds for callers that go through that specific code path. The
cost is that constraint violations surface as exceptions that the service layer must
translate into the correct HTTP response (200 for a duplicate `external_ref`, 400 for an
invalid confidence) — that translation is a small, contained cost paid once per constraint.
