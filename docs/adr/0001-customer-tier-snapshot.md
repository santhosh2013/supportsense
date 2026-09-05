# ADR-0001: `customer_tier` as a point-in-time snapshot

**Status:** Accepted

## Context

Abstention must fire for enterprise-tier customers (FR-6), but `Ticket` has no natural link
to a customer entity in A1 — only `customerEmail`. Customer tier could be looked up live
from a customer record, or captured on the ticket at ingestion time.

## Decision

`tickets.customer_tier` is a **denormalised snapshot**, not a foreign key to a customer
entity. It is set once, at ingestion, from the tier the customer held **at that moment**.

## Consequences

A ticket is judged by the tier held **when it was raised**, even if the customer
subsequently upgrades or downgrades. This is deliberate: retroactively changing how an
already-triaged ticket would have been handled, because the customer's plan changed later,
is the wrong behaviour for an audit trail. The trade-off is that `customer_tier` can drift
from the customer's current plan — that drift is the point, not a bug, and is recorded here
so it does not look like an accident.
