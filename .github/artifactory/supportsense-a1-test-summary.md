# E2E Test Summary — supportsense-a1

**Phase:** 5 (E2E Testing)
**Mode:** Dual-model blind journey-layer suite (`blueprint-testing` + `blueprint-testing-altmodel`, run independently, no cross-visibility)
**Framing:** black-box, HTTP-only journeys — no repository/EntityManager/service-bean access anywhere in either suite
**CI status:** ✅ Green on GitHub Actions (after two test-bug fixes found on the first real run)

---

## Scope decision

Both blind models independently found that 4 of 6 originally-mandated journeys could not be
asserted black-box, for reasons rooted in what A1 actually implements — not testing gaps:

- No triage exists in A1, so no ticket is ever assigned a team, so no AGENT can ever see a
  ticket they created.
- No lifecycle-transition endpoint exists.
- `AutoAnswerGate`/`PreScreenMatcher` have no production caller; no `triage_results` row is
  ever written.
- Fallback-team recovery requires 3 failed attempts plus a reaper pass on hardcoded 60s
  intervals with a 15-minute stale threshold — unreachable in test time without touching
  internals, which was explicitly forbidden.

Per user direction, these were **not worked around** — no journey uses repository/JDBC/bean
access to compensate. Instead:

- Recorded as formal API findings in [README.md](../../README.md) (`API-F1`–`API-F5`), each
  with the journey blocked, what's unobservable, why it matters to a real client (not just a
  test), and the smallest A2 fix.
- One narrowly-scoped exception was implemented: `ingestionState` added as a read-only field
  on `TicketResponse`, justified independently of testing — a 202 response with no way to
  observe the accepted work's outcome is an incomplete contract on its own merits. Documented
  in `src/main/resources/openapi.yaml` and covered by the contract-drift check.
- `GET /api/teams` was explicitly **not** built — recorded as `API-F5` instead, per user
  instruction not to add product to satisfy a test.

## Real defect found during E2E design (not a test artifact)

Both blind models independently observed that every unauthenticated/invalid-credential
request (missing header, expired token, tampered signature, forged `alg:none`) returned
**403 instead of 401**. Verified against source before acting: `SecurityConfig` registered no
`AuthenticationEntryPoint`/`AccessDeniedHandler`, so Spring Security's default
(`Http403ForbiddenEntryPoint`) applied uniformly to both "no credentials" (should be 401 per
RFC 7235) and "authenticated but wrong role" (403, correctly). `JwtAuthenticationFilter`
compounded this by silently clearing the security context on an invalid token instead of
rejecting the request.

**Fixed:** explicit `AuthenticationEntryPoint` (401, generic body, `WWW-Authenticate: Bearer`)
and `AccessDeniedHandler` (403), both rendering the same RFC-7807 `ProblemDetail` shape as
`GlobalExceptionHandler`, via a shared `SecurityResponseWriter`. Regression-covered by
`JwtRejectionStatusCodeIT`, including a same-body assertion across all four rejection modes
so a differing error body can't become a credential-probing oracle. Cross-team 404-not-403
and wrong-role 403 re-verified unaffected.

Full line-by-line re-audit of `SecurityConfig` also surfaced two forward-looking findings,
recorded as `API-F6` (CORS unconfigured — harmless until A6's Angular console) and `API-F7`
(no login rate-limiting — never in A1 scope, not a regression).

## Journeys — final list, by category

| Category | Model A (`e2e`) | Model B (`e2ealt`) |
|---|---|---|
| Negative-auth matrix | `NegativeAuthMatrixIT` — 7 cases | `NegativeAuthMatrixAltIT` — 7 cases |
| Idempotency (BR-A02) | `TicketIdempotencyJourneyIT` — sequential + 2-thread concurrent | `TicketIdempotencyJourneyAltIT` — sequential + 5-thread concurrent |
| Bulk partial success | `BulkIngestionJourneyIT` — mixed batch + 501-item rejection | `BulkIngestionJourneyAltIT` — mixed batch + 501-item rejection |
| Async completion (`ingestionState`) | `AsyncIngestionCompletionIT` | `AsyncIngestionCompletionAltIT` |
| Page envelope / list | `TicketListPageEnvelopeIT` — 2 cases (envelope shape, size clamp) | `TicketListPageEnvelopeAltIT` |
| OpenAPI contract drift | `OpenApiContractDriftIT` | `OpenApiContractDriftAltIT` |
| 401/403 regression (added after the finding above) | `JwtRejectionStatusCodeIT` — 6 cases | *(shared, not duplicated per model)* |

### Journeys common to both models (converged independently)
- Wrong-role AGENT → 403 on bulk
- Missing/expired/tampered/`alg:none` token → 401 (after the fix; both initially found 403 pre-fix)
- Refresh-token reuse → family revocation, both halves 401
- Untriaged-ticket invisibility as the cross-team-404 fallback (both independently concluded true cross-team could not be constructed black-box, for the identical reason: no team-listing endpoint)
- Sequential + concurrent idempotency on `externalRef`
- Bulk mixed-outcome batch + 501-item rejection
- Async completion via `ingestionState` polling with Awaitility
- Page envelope shape
- OpenAPI structural drift (path+method presence, both directions)

### Unique to Model B
- 5-thread concurrent idempotency (vs. Model A's 2-thread) — broader concurrency coverage
- Explicit page-size-clamp case (`size=1000` → clamped to 100)
- OpenAPI drift test also cross-checks documented response codes against empirically observed codes (caught the 401/403 documentation implication directly)

### Unique to Model A
- None structurally unique — Model A's suite is a subset in scope but not weaker in assertion rigor per journey

## Bugs found on the first real CI run (both were test bugs, not production defects — verified against source before fixing)

| Test | Bug | Root cause | Fix |
|---|---|---|---|
| `JwtRejectionStatusCodeIT.allRejectionModesReturnIdenticalBodies` | Compared raw ProblemDetail JSON strings | `timestamp` is real wall-clock time and differs by nanoseconds between separate HTTP calls | Parse both bodies, strip `timestamp`, compare the rest |
| `TicketIdempotencyJourneyAltIT.concurrentDuplicatePostsConvergeToSameTicket` | Follow-up `GET` used the creator's own AGENT token | A freshly ingested ticket has `team_id = NULL` (no triage in A1); `visibleTo` hides untriaged tickets from every AGENT, including its creator — this is `API-F1` surfacing as a test bug | Follow-up read now uses the seeded ADMIN token |

Swept every other file in both suites for the same "creator reads back their own ticket"
pattern — `AsyncIngestionCompletionIT`/`AltIT` and `BulkIngestionJourneyIT`/`AltIT` already
used the correct ADMIN token. Isolated to the one test.

## Flake detection

CI runs the Failsafe integration suite twice in the same job — the second run writes to a
separate reports directory (`target/failsafe-reports-rerun`) so it never overwrites the
primary run's JaCoCo merge data or `dorny/test-reporter` input. No flake reported after the
fixes above; both runs green.

## What could not be asserted black-box (full detail)

See [README.md](../../README.md) § "API observability gaps found during E2E design" for the
complete table (`API-F1` through `API-F7`) — journey blocked, what's unobservable, why it
matters to a real client, and the smallest A2 fix for each.

## Final verdict

✅ **CI green.** Full negative-auth matrix, idempotency (sequential + concurrent), bulk
partial success, async completion, page envelope, and OpenAPI contract drift are all
verified against real PostgreSQL Testcontainers, twice per CI job with no flake. Exact test
counts from this specific green run were not captured from a pasted log — user confirmed
success without pasting it — so this summary records pass/fail status and content, not
invented numbers.

**Proceed to Phase 6 (Security).**
