# ADR-0013: Pure domain core and injected clock

**Status:** Accepted

## Context

Ticket lifecycle transitions, sensitive-topic pre-screening, confidence thresholds, RRF,
and other rules need fast, deterministic tests without a Spring context, ORM, database, or
wall-clock dependency. Inline calls to `Instant.now()` make SLA, reaper, and 48-hour
false-deflection calculations hard to test without waiting.

## Decision

Domain classes under `..domain..` have zero Spring and zero JPA/Hibernate imports. The
boundary is enforced by ArchUnit. Time-dependent application logic receives `TimeSource`,
backed by an injected UTC `Clock`; production code is forbidden by ArchUnit from calling
`Instant.now()`, `LocalDateTime.now()`, `LocalDate.now()`, or `System.currentTimeMillis()`.

## Consequences

Rules are unit-testable without infrastructure and time-dependent behavior can be evaluated
deterministically. The cost is passing time explicitly rather than calling static clock
methods inline. This is accepted because the reaper threshold, SLA timing, and
false-deflection window are business-critical boundaries, not incidental timestamps.
