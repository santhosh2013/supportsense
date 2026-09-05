# ADR-0010: Package by feature, not by layer

**Status:** Accepted

## Context

A Spring Boot project can be organised by technical layer (`controllers/`, `services/`,
`repositories/`) or by business feature (`ticket/`, `auth/`, `triage/`), each owning its own
layers internally.

## Decision

Package by feature: `ticket/{web,app,domain,persistence}`, `auth/{web,app,domain,persistence}`,
and so on. No top-level `controllers/`, `services/`, or `repositories/` packages exist
anywhere in the codebase.

## Consequences

Related code changes together — adding a field to `Ticket` touches files inside `ticket/`,
not four different top-level packages. Module boundaries between `ticket`, `auth`, and
`triage` are visible in the package structure itself, and later features (`kb/`,
`retrieval/`) slot in the same way. The trade-off is unfamiliarity for developers used to
the layered convention most Spring tutorials teach — mitigated by keeping the same
`web/app/domain/persistence` sub-structure inside every feature package, so the convention
is learned once.
