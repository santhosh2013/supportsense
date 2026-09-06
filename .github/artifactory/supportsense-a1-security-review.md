# Security Review — supportsense-a1

**Phase:** 6 (Security)
**Mode:** Dual-model blind audit (two independent `blueprint-security` runs, no cross-visibility), same protocol as Phase 5
**Method:** Static/manual source review — no automated scanners invoked
**Result:** 1 real authorization defect found and fixed; all other findings were Low/Informational or immaterial to A1's current scope

---

## Executive summary

Both models independently audited JWT/session security, authorization enumeration,
`SecurityConfig` line-by-line, injection/data-exposure, and secrets/history. They converged
on nearly every finding — a strong signal given the two prior Phase 5 findings (401-as-403,
inert method security) also came from independent convergence. **One material divergence**
surfaced: Model A found a real, previously-undetected authorization gap (unauthenticated
self-registration with an unvalidated `teamId`) that Model B completely missed. Verified
against source myself before acting — confirmed real, fixed, regression-tested.

One of Model B's own findings (missing `@Valid` on the bulk endpoint) was independently
verified to be a **false positive** — it read only the controller signature and didn't
trace into `BulkIngestionService`, which performs manual per-item validation by design
(a Phase 4 remediation decision, made precisely so a malformed item produces a `REJECTED`
outcome instead of failing the whole batch).

## Real defect found and fixed

### SEC-1 (Medium→fixed) — Unauthenticated team self-assignment on registration

`POST /api/auth/register` is `permitAll` and previously accepted a client-supplied `teamId`,
bound directly onto the new user with no validation (`AuthService.register()`:
`teamRepository.findById(request.teamId()).orElse(null)`). Since
`TicketSpecifications.visibleTo()` grants an AGENT full read access to their team's ticket
queue, any anonymous caller could self-register into an arbitrary existing team and
immediately inherit its visibility.

**Verified before fixing, not assumed:**
- `RegisterRequest` has no `role` field at all — `AuthService.register()` hardcodes
  `UserRole.AGENT`. Full privilege escalation via role is **not possible**; the gap was
  scoped to `teamId` alone.
- Swept every request DTO in the codebase (`LoginRequest`, `RefreshRequest`,
  `RegisterRequest`, `CreateTicketRequest` — the complete set) for the same
  mass-assignment pattern. Only `RegisterRequest.teamId` was exploitable;
  `CreateTicketRequest.customerTier` is a non-security-relevant business field, and no
  create-ticket field maps to `team`/`category`/`status`/`priority`/`assigneeId` (all
  server-controlled).
- Checked actual blast radius: in A1's current state, no production code assigns a
  non-null team to any ticket except `OrphanTicketService`'s fallback-team routing after 3
  failed ingestion attempts — normal tickets never get a team. Confirmed via
  `git log --all -p` that no seed/migration ever inserted a user with a team, so there is
  no pre-existing incident. **The narrow impact is an artifact of A1 having no triage, not
  a property of the defect** — A2 introduces triage-to-team assignment, at which point this
  same unchanged code would expose every ticket in the system to any anonymous registrant.

**Fix:** `RegisterRequest` gained a `@AssertTrue` bean-validation constraint rejecting any
non-null `teamId` with 400 (fail loud, not silently ignored — a client that thinks it
joined a team must be told it did not). Team assignment becomes an authenticated,
admin-only action in A2 — recorded in
[supportsense-a2-security-input.md](supportsense-a2-security-input.md) so it isn't
deferred-and-forgotten.

**Regression tests** (`RegistrationTeamAssignmentSecurityIT`):
- Registration with a non-null `teamId` → 400, and no user row created (asserts the
  absence, not just the status code).
- Registration with a null `teamId` → 201, and the resulting user has `teamId == null`.
- Existing cross-team 404-not-403 behavior re-verified unaffected (both audit models traced
  this precisely — see §Authorization below).

**Process note, as requested:** this gap was found by one model and missed by the other,
and neither model's impact writeup traced what data actually reaches the exposed scope
before assigning severity — I did that tracing myself before deciding a fix. Single-model
coverage on the authorization surface is not sufficient on its own; independent blind
convergence catches some gaps (401/403, inert method security) but not all — a defect can
exist and only one of two independent reviewers finds it. Recording this as a limitation of
the review method, not just a finding about the code.

---

## JWT / session security — findings table (reconciled from both models)

| Item | Verdict | Reasoning |
|---|---|---|
| Algorithm pinned, `alg:none` rejected | ✅ PASS | `.signWith(signingKey, Jwts.SIG.HS256)`; `verifyWith(SecretKey)` structurally requires HMAC. Proven by `JwtAlgorithmSecurityTest` with a real forged token, not just reasoned from docs. |
| Signature verified before claims trusted | ✅ PASS | Single fluent chain `Jwts.parser().verifyWith(...).build().parseSignedClaims(token).getPayload()` — no code path reads a claim pre-verification. |
| `exp` enforced | ✅ PASS | JJWT's default parser throws `ExpiredJwtException` automatically; caught as `JwtException` in the filter. |
| `nbf` set/enforced | CONCERN — immaterial | No pre-dated/deferred-activation issuance flow exists; tokens are self-issued and immediately valid. |
| `iat` staleness validated | CONCERN — immaterial | Only informational; `exp` already caps lifetime to 15 minutes. |
| Clock-skew tolerance | CONCERN — Low, real but minor | JJWT default = 0 skew. Single-instance-equivalent deployment today; add a few seconds of tolerance defensively before horizontal scaling. |
| `iss`/`aud` validated | CONCERN — legitimate no-op today, flag for A2+ | Single first-party issuer/audience, no federation yet. Becomes real the moment a second service consumes these tokens. |
| Signing key source + fail-fast outside `local` | ✅ PASS | `RequiredSecretsValidator` fails startup via `ApplicationEnvironmentPreparedEvent`, before any bean construction, for every profile except `local`. |
| Refresh rotation revokes entire family on reuse | ✅ PASS | Traced precisely: `REUSE_DETECTED` → `revokeFamily(familyId)` loads and revokes **every** row sharing the family id, not just the presented one. |
| Family revocation survives caller's transaction | ✅ PASS | `PROPAGATION_REQUIRES_NEW` — the revoke commits independently of `AuthService.refresh`'s `BadCredentialsException`. |
| Revoked family cannot be resurrected | ✅ PASS | No `unrevoke`/`clearRevokedAt` code path exists anywhere; `issue()` always creates a new row; DB-level `UNIQUE` index on `token_hash` makes hash-collision resurrection cryptographically and structurally infeasible. |
| Access/refresh TTLs reasonable | ✅ PASS | 15-minute access token, 7-day rotating refresh — standard and reasonable. |

## Authorization — enumeration and re-verification

**Every `@PreAuthorize` in main source** (3 total, both models found the identical set):
`TicketService.listVisibleTickets` (`isAuthenticated()`), `TicketService.getVisibleTicket`
(`isAuthenticated()`), `TicketController.createBulk` (`hasAnyRole('ADMIN','SERVICE')`) — all
confirmed enforced now that `@EnableMethodSecurity` is active.

**Cross-team 404-not-403, re-verified after the 401 fix (both models traced this
independently and reached the same conclusion):** `@PreAuthorize("isAuthenticated()")` on
`getVisibleTicket` can only ever produce a 403 for a fully-anonymous principal, which is
already impossible at that point (the filter chain's `anyRequest().authenticated()` already
gated it, producing 401 first). The actual team-isolation logic lives entirely in
`TicketSpecifications.visibleTo` — a cross-team ticket is excluded from the query result
set, not "found but forbidden," so `.orElseThrow(() -> 404)` is the only path that can fire.
**Confirmed: no code path where this produces 403 instead of 404.**

**No unintentionally-public endpoint found.** Every mapping is either explicit `permitAll`
or falls to `anyRequest().authenticated()`. `/actuator/health` is the only public actuator
endpoint; `/actuator/info`, `/prometheus`, `/metrics` require authentication (any role) —
both models flagged this as correct-by-construction but not explicitly asserted by a test;
recorded as a Low/Informational hardening note below.

## `SecurityConfig` full re-audit

| Configuration | Verdict |
|---|---|
| `@EnableMethodSecurity` | ✅ Correct — active, confirmed by both models |
| `BCryptPasswordEncoder(10)` | ✅ Correct/adequate — meets OWASP minimum |
| CSRF disabled | ✅ Correct — justified for stateless Bearer-token API, no cookie auth exists |
| Session policy `STATELESS` | ✅ Correct |
| `permitAll` matcher list (bare paths + `/**` variants) | ✅ Correct — Spring Security 6 path-matching semantics verified character-by-character by both models independently |
| `exceptionHandling()` — entry point + access-denied handler | ✅ Correct — both configured, both render identical generic ProblemDetail bodies, `WWW-Authenticate: Bearer` set only on 401 |
| `JwtAuthenticationFilter` — no silent-anonymous fallthrough on invalid token | ✅ Correct — throws through the entry point; only a non-`Bearer`-prefixed header falls through as anonymous (by design, and still correctly denied downstream) |
| CORS | Missing-but-immaterial today — no browser client exists in A1; must be added before A6 |
| Login rate-limiting | **Missing — real, actionable finding (Medium)** — no lockout/throttle on `/api/auth/login`; not fixed this pass, recorded below |
| Stated-intent vs. actual-behavior mismatches | None found — every code comment matched observed behavior in both audits |

## Injection and data exposure

- **Zero string-concatenated SQL/JPQL found.** All 5 `@Query` annotations use named
  parameter binding; `TicketSpecifications` uses `CriteriaBuilder` exclusively. No raw
  `JdbcTemplate`/`Statement` in main source.
- **No secret/token/password ever logged.** Every `log.*` call in the auth package checked;
  only non-sensitive identifiers (email, familyId UUID) are logged.
- **No stack trace or internal message reaches a response body.** `server.error.include-message: never`,
  `include-stacktrace: never`; `GlobalExceptionHandler`'s catch-all always returns a fixed
  string, never `exception.getMessage()`.
- **401 response body confirmed identical across all rejection modes** (missing/expired/tampered/forged-alg)
  — traced precisely by both models; the entry point bean doesn't even inspect the passed
  exception. No credential-probing oracle exists.
- **False positive corrected:** Model B flagged `POST /api/tickets/bulk` for missing
  `@Valid` on the request list. Verified against `BulkIngestionService.ingestItem()` — this
  is a deliberate architectural choice (manual per-item `Validator` invocation), not a gap.
  Removed from the findings list.

## Secrets and git history

Both models had real, independent git/terminal access and confirmed:
- No real secret (AWS keys, PEM headers, API tokens) found anywhere in the working tree or
  full commit history (`git log --all -p` across all 23 commits).
- Only known-fake fixture values appear anywhere, and have never changed across history.
- CI's gitleaks step verified directly from `.github/workflows/ci.yml`: `fetch-depth: 0`
  (full history) + `gitleaks detect` (not `gitleaks protect`, which only diffs).

**No action needed — this area is clean.**

---

## Severity-ranked findings (final, reconciled)

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | **Medium → Fixed** | Unauthenticated self-registration allowed arbitrary `teamId` self-assignment | ✅ Fixed this phase, regression-tested |
| 2 | **Medium** | No rate-limiting/brute-force protection on `POST /api/auth/login` | Recorded as API-F7 in README (pre-existing, not fixed this phase — genuinely unscoped for A1, not a regression) |
| 3 | **Low** | No CORS configuration exists anywhere | Recorded as API-F6 in README — immaterial until A6's browser client |
| 4 | **Low** | No `iss`/`aud` claims on issued JWTs | Recorded in A2 security input — immaterial until a second token-consuming service exists |
| 5 | **Low** | No JWT clock-skew leeway configured | Informational — low-risk given 15-min TTL and single-instance-equivalent deployment |
| 6 | **Informational** | `/actuator/prometheus`/`/metrics` require auth (correctly) but no explicit test asserts this | Hardening suggestion — add a guard test before any future matcher-list refactor |
| 7 | **Informational** | `nbf`/`iat` staleness not enforced | Immaterial for current self-issuing, short-TTL, non-federated design |
| 8 | **Informational** | BCrypt cost factor 10, not 12 | Adequate today; consider raising in a future pass |

**No Critical or High findings.**

## What I'm not confident about

- **JJWT library-internal exception message content** (whether `JwtException` subtypes ever
  embed raw JWT fragments in their message when logged at DEBUG) — this depends on the
  `io.jsonwebtoken` library's own source, which neither audit inspected directly. Practical
  risk is low (DEBUG level, not active in the `cloud` profile), but this is inferred from
  general library behavior, not verified against the library's own source.
- **CORS "no default is permissive" claim** — both models reasoned this from general Spring
  Security knowledge and confirmed no `.cors()`/`CorsConfigurationSource` exists in source,
  but neither empirically tested a live preflight `OPTIONS` request against a running
  instance. High confidence, not absolute.
- **Whether the actuator auth-level split (`health` public, others authenticated) was a
  deliberate per-endpoint decision** — no commit message or comment explicitly discusses
  this; it appears correct by construction (deny-by-default) but wasn't found to be an
  explicitly stated design choice.
- **The one real divergence itself** — I verified the team-assignment gap and its blast
  radius against source myself, but did not empirically run the exploit against a live
  instance (no Docker available locally); the fix's correctness rests on the compile-clean
  `@AssertTrue` constraint plus the new regression test, which is CI-only until pushed.

---

## A1 Close-out

### Business rule → covering test

| Rule | Enforcement | Covering test(s) |
|---|---|---|
| BR-A01 | Persist-first async dispatch, bounded executor, sweep/reaper | `TicketIngestionAfterCommitIT`, `IngestionRejectionIT`, `IngestionClaimConcurrencyIT`, `IngestionReaperIT`, `AsyncIngestionCompletionIT`/`AltIT` |
| BR-A02 | `ux_ticket_external_ref` idempotency | `TicketIdempotencyConcurrencyIT`, `SchemaConstraintsIT`, `TicketIdempotencyJourneyIT`/`AltIT` |
| BR-A09 | Pure lifecycle transition map | `TicketStatusTransitionsTest` |
| BR-A10 | `TicketSpecifications.visibleTo`; 404 for inaccessible tickets | `TicketVisibilityIT`, `TicketApiSecurityIT`, `NegativeAuthMatrixIT`/`AltIT` |
| BR-A12 foundation | Versioned classpath prompt resources | `PromptResourceLoaderTest` |
| Sensitive-topic guard foundation | Configured whole-word pre-screen OR category block | `PreScreenMatcherTest`, `AutoAnswerGateTest` |
| Method security enforcement | `@EnableMethodSecurity` + role checks | `TicketMethodSecurityIT` |
| Auth failure status codes (401 vs 403) | Explicit `AuthenticationEntryPoint`/`AccessDeniedHandler` | `JwtRejectionStatusCodeIT` |
| **Registration team-assignment gate (new)** | `@AssertTrue` on `RegisterRequest` | `RegistrationTeamAssignmentSecurityIT` |
| OpenAPI contract parity | Hand-maintained spec vs. generated | `OpenApiContractDriftIT`/`AltIT` |

### ADR list with status

15 ADRs total (`docs/adr/README.md`), ADR-0002 marked Superseded by ADR-0015, all others
Active/Completed as of the last checkpoint update — no ADR changed during Phase 6 (the
team-assignment fix is a bug fix within existing design intent, not a new architecture
decision).

### Merged coverage %

Not captured from a pasted CI log this phase — consistent with this project's standing
rule of not inventing numbers. Re-run CI and capture the JaCoCo merged summary if an exact
figure is needed.

### Deferred items

| Item | Where recorded |
|---|---|
| API-F1–F5 (triage→team, lifecycle endpoint, abstention exposure, fallback-recovery observability, teams-listing) | README.md |
| API-F6 (CORS) | README.md |
| API-F7 (login rate-limiting) | README.md |
| SEC-A2-1–4 (prompt injection, PII-to-provider, denial-of-wallet, untrusted model output) | supportsense-a2-security-input.md |
| SHF-6 (test-only credential fixtures) | README.md, from Phase 4 |

---

## Final verdict

✅ **1 Medium finding fixed and regression-tested this phase.** No Critical/High findings.
All other findings are Low/Informational, correctly scoped as forward-looking or
genuinely out of A1's boundary — not fixed reflexively, and not swept under a "deferred"
label without a specific tracked location. Pending CI verification (local unit suite
65/65 green; `*IT.java` requires the next push).

**A1 is functionally complete through Phase 6.** Proceed to A2 Phase 1 (Requirements) once
CI confirms this phase's fixes.
