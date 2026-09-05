# ADR-0005: Defer API-key authentication

**Status:** Accepted

## Context

Sheet 06 lists `/api/tickets` as accepting "any / API key", implying machine-to-machine
ingestion should not require a human's JWT. Building API-key auth properly (hashed key
storage, scoped permissions, separate rate limiting) is real work that competes with A1's
core scope.

## Decision

A1 is **JWT-only**. Machine ingestion authenticates as a user holding a `SERVICE` role
(`User.role` becomes `{AGENT, LEAD, ADMIN, SERVICE}`). Proper API-key support is deferred,
with its intended design recorded here so the reasoning is not lost.

## Intended design (for a future milestone)

- Key stored as a SHA-256 hash, never in plaintext
- A short, non-secret prefix stored alongside the hash for fast lookup without a full-table
  scan
- Each key scoped to a single team
- Rate-limited separately from JWT-authenticated traffic

## Consequences

A `SERVICE`-role user is a pragmatic stand-in with weaker properties than a real API key
(no per-key revocation, no distinct rate limit), acceptable because it is a production
hardening concern rather than something that changes the demonstrated architecture. Revisit
in A6 only if time allows.
