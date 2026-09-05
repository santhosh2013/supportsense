# ADR-0007: Testcontainers, CI-first execution

**Status:** Accepted

## Context

The development machine has no Docker runtime available (`docker ps` fails with
`CommandNotFoundException`), and Docker Desktop may be blocked by corporate licensing
policy. Sheet 01's non-negotiables require Testcontainers, never H2, for behavioural
fidelity with production Postgres.

## Decision

Integration tests requiring Docker are written exactly as if Docker were available locally
— tagged `@Tag("integration")` and named `*IT.java` — and are **never** disabled and
**never** rewritten against H2. GitHub Actions on `ubuntu-latest` (where Docker is
available) becomes the execution venue, and the CI workflow ships in Batch 1 rather than
being deferred until later.

## Consequences

The feedback loop for integration-level bugs is slower — a failing Testcontainers test is
only visible after a CI run, not on the developer's machine. This is mitigated by: (1) rich
unit and `@WebMvcTest` coverage that catches most bugs locally with no Docker, and (2)
CI publishing Surefire/Failsafe test reports on failure, so a red build is diagnosable
without needing to reproduce it locally at all. Batch 1's walking-skeleton test exists
specifically to prove this pipeline works before any business logic is written on top of it.
