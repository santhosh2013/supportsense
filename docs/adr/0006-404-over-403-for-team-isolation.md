# ADR-0006: 404 over 403 for team isolation

**Status:** Accepted

## Context

BR-A10 requires that a user may only see tickets belonging to their team unless they are
`LEAD` or `ADMIN`. When an `AGENT` requests a ticket outside their team, the API can return
either 403 (Forbidden — the resource exists, but you may not see it) or 404 (Not Found — as
far as you are concerned, this does not exist).

## Decision

Return **404**, never 403, for both cross-team ticket access and untriaged-ticket access by
an `AGENT`.

## Consequences

403 confirms that a resource with that ID exists, which is itself information the caller is
not entitled to — an attacker can enumerate valid ticket IDs by watching for the 403 vs. 404
distinction across teams. 404 leaks nothing. The cost is a slightly less helpful error for a
legitimate user who mistyped an ID versus one who is out of scope — both look identical. That
trade-off favours the caller's security over marginally better error diagnostics.
