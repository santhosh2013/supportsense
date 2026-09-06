# Pipeline Checkpoint — supportsense-a1

## State
- Phase completed: **6 (Security) — CLOSED, pending CI verification**
- Next phase: **A2 Phase 1 (Requirements)**
- Slug: `supportsense-a1`
- Codebase: `c:\Users\ASANTH16\Downloads\Project1` (empty — greenfield)
- Project type: Backend API (Spring Boot 3.3 / Java 21); Angular console deferred to A6
- Source: `C:\Users\ASANTH16\Downloads\TwoProject_Build_Spec.xlsx` — sheets 01–08, 13–16

## Scope
- **Milestone A1 only** — domain, schema V1–V4, JWT auth, async ingestion, CI
- P2 AssetOps explicitly out of scope (separate repo, no shared code)
- A2–A6 deferred

## Artifacts
- Requirements: `.github/artifactory/supportsense-a1-requirements.md` (Approved, amended 2026-09-05)
- Design: `.github/artifactory/supportsense-a1-design.md` (Approved)
- Execution plan: `.github/artifactory/supportsense-a1-execution-plan.md` (Approved, 7 batches)
- Review summary: `.github/artifactory/supportsense-a1-review-summary.md` (APPROVE)
- Review summary (alt-model): `.github/artifactory/supportsense-a1-review-summary-altmodel.md`
- Test summary: `.github/artifactory/supportsense-a1-test-summary.md` (CI green, dual-model blind journey suite)
- OpenAPI contract of record: `api/src/main/resources/openapi.yaml`
- Security review: `.github/artifactory/supportsense-a1-security-review.md` (1 Medium finding fixed, dual-model blind audit)
- A2 security requirements input: `.github/artifactory/supportsense-a2-security-input.md`

## Architecture
**Approach C — feature-sliced modular monolith with a pure domain core**, ArchUnit-enforced.

- Packages: `ticket/`, `kb/`, `triage/`, `retrieval/`, `auth/`, `common/`; each with `web/app/domain/persistence`
- Pure domain classes: **zero** Spring imports, **zero** JPA imports (ArchUnit-verified)
- `Clock` injected everywhere — no inline `Instant.now()` (ArchUnit-verified)
- Coordinates: `io.github.santhosh2013` / `supportsense-api` / `io.github.santhosh2013.supportsense`
- Layout: repo root `Project1/`; `api/`, `web/` (A6), `infra/`, `eval/`, `docs/`, `.github/workflows/`

## Locked Decisions
- **Ingestion:** persist-first — the ticket row IS the work item; dispatch via `@TransactionalEventListener(AFTER_COMMIT)`
- **Two state columns:** `status` (BR-A09 lifecycle) vs `ingestion_state` (`PENDING/PROCESSING/DONE/FAILED`). `PROCESSING` deliberately NOT in `Ticket.status`
- **Executor:** core 4 / max 8 / queue 500, **`AbortPolicy`** (never `CallerRunsPolicy`); `TaskDecorator` propagates MDC + `SecurityContext`; `AsyncUncaughtExceptionHandler` persists `FAILED`
- **Rejection ⇒ 202** + `ingestion.queue.rejected` counter (ADR-0015 supersedes ADR-0002's 503)
- **Claim:** conditional `UPDATE ... WHERE ingestion_state='PENDING'`, proceed only if 1 row affected — no distributed lock
- **Sweep** 60 s + startup; **Reaper** 60 s, 15-min stale threshold, attempt cap 3 ⇒ `FAILED`
- **Orphan prevention:** 3 failures or `NEW` > 1 h ⇒ fallback team Customer Success, `TOOL_FAILURE`
- **BR-A10:** single `TicketSpecifications.visibleTo(principal)` fragment on every read; cross-team AND untriaged ⇒ **404**
- **DB invariants:** `ux_ticket_external_ref`, canonical `ticket_a_id < ticket_b_id` CHECK+UNIQUE, `numeric(4,3)` + range CHECKs
- **Reopen index predicate excludes `RESOLVED→CLOSED`** (forward closure, not a reopen)
- **Refresh tokens:** SHA-256 hashed, rotate on use, reuse ⇒ revoke family
- **Extra `tickets` columns:** `customer_tier`, `auto_answered`, `auto_answered_at`, `first_resolved_at`, `ingestion_state`, `claimed_at`, `attempt_count`, `ingestion_error`
- **Testing:** `@Tag("integration")` + `*IT.java`; `mvn test` = no Docker; `mvn verify` = CI. Never `@Disabled`, never H2
- **JaCoCo MERGED** across Surefire + Failsafe (`prepare-agent` + `prepare-agent-integration` + `merge`), 60 % floor on merged data
- **Roles:** `AGENT`, `LEAD`, `ADMIN`, `SERVICE`
- **Local DB:** Neon via `SPRING_DATASOURCE_URL` + `sslmode=require`, never committed
- **AI providers:** Ollama (`local`) + Gemini (`cloud`) behind `ChatModel`/`EmbeddingModel`, 768 dims; A1 needs no live model

## Build Conventions
- **One batch = one PR.** Conventional commits. No squashing batches together.
- ADRs written **as implemented**, never retrospectively. Superseded ADRs marked, never deleted.
- 15 ADRs total; index at `docs/adr/README.md`

## Batch 1 — Non-negotiable inclusions
1. Walking-skeleton Testcontainers IT green **on the CI runner** (pgvector image + Flyway + `/actuator/health` UP)
2. JaCoCo merged reporting configured **now**
3. ArchUnit rules present **from the start**
4. ADR index seeded with all decisions already made
5. Own PR with a conventional commit message

## Open Questions
None — OQ-1..OQ-4 resolved; FR-5/AC-6 delta amended into requirements.

## Batch Progress
- **Repo live:** `github.com/santhosh2013/supportsense` (private, personal account). Layout confirmed: `api/`, `docs/`, `infra/`, `.github/workflows/`.
- **Batch 1 (skeleton, CI, ADR index): ✅ VERIFIED GREEN IN CI.** `WalkingSkeletonIT` executed for real on `ubuntu-latest` — Testcontainers `pgvector/pgvector:pg16`, Flyway V1–V4 applied, `/actuator/health` UP. Merged JaCoCo report produced.
- **Batch 2 (V1/V2 migrations, JWT auth, refresh-token rotation): ✅ VERIFIED GREEN IN CI.** `AuthFlowIT` (register/login/generic-failure-message/**token-family-revocation-on-replay**) and `SeedIdempotencyIT` both executed and passed on the runner.
- **Batch 3 (V3/V4 migrations, ticket entities) + constraint-test follow-up: ✅ VERIFIED GREEN IN CI.** `SchemaConstraintsIT` — all 13 tests (confidence bounds/rounding, canonical duplicate-pair CHECK incl. reversed-pair rejection, `external_ref` uniqueness, reopen-index predicate) executed and passed.
- **CI infrastructure bugs found and fixed during first real runs** (not code-quality issues — genuine environment/tooling gaps only surfaced by execution):
  1. gitleaks-action's before/after diff range unresolvable on a repo whose history was replaced → switched to a direct CLI full-history SARIF scan (also satisfies FR-9 literally)
  2. `mvnw` missing the Unix executable bit (git-on-Windows doesn't preserve it) → `chmod +x` step added to CI
  3. `User`/`Ticket`/`Team`/`Category`/`TriageResult.createdAt` never set in Java → Hibernate inserted explicit `NULL`, overriding the DB `DEFAULT now()` → added `@CreationTimestamp`
  4. `GlobalExceptionHandler`'s catch-all swallowed `ResponseStatusException` (e.g. AuthService's 409) into a generic 500 → added a specific handler ahead of the catch-all
  5. `/api/auth/refresh` missing from `permitAll()` → added
  6. JDK `HttpURLConnection` has hardcoded retry logic for 401/407 that breaks on streamed request bodies → switched `AuthFlowIT`'s `TestRestTemplate` to Apache HttpClient5
  7. `dorny/test-reporter`'s `*.xml` glob matched `failsafe-summary.xml` (not a JUnit report) → narrowed to `TEST-*.xml`
  8. Redis health check failing `/actuator/health` in CI (Redis wired per sheet 03 but not exercised until A6) → `management.health.redis.enabled: false` scoped to the `test` profile only
  9. **Real transactional bug:** `AuthService.refresh()` throws `BadCredentialsException` inside the *same* transaction as `RefreshTokenService.revokeFamily()`'s write — default rollback silently undid the family revocation on token-theft detection, meaning the 401 was correct but the security-critical side effect never persisted. Fixed with `TransactionTemplate` + `PROPAGATION_REQUIRES_NEW` (not `@Transactional`, since `revokeFamily` is called via private self-invocation which bypasses the Spring proxy).
- **User rejected switching to H2** — correctly: violates sheet 01 non-negotiables and ADR-0007, would invalidate `SchemaConstraintsIT`'s PostgreSQL-specific assertions, and doesn't address the actual constraint (no local Docker) any better than Neon already does.
- **Batch 4 (ticket lifecycle & BR-A10 visibility): code complete, 39/39 local tests green.** `TicketStatusTransitions` (pure, BR-A09 — 16 unit tests incl. exhaustive pair coverage). Single `TicketSpecifications.visibleTo(principal)` predicate composed into `TicketRepository.findAll`/`findOne` via `@EntityGraph`-annotated overrides (FR-4 no-N+1). `TicketService`/`TicketController` — GET list (paginated, max 100) and GET by id, 404-not-403 for both cross-team and untriaged access. New ArchUnit rule: only `ticket.app`/`ticket.persistence` may reference `TicketRepository` — guards against BR-A10 bypass. Also fixed a false-positive in the existing `webDoesNotDependOnPersistence` rule (narrowed to `@Entity` classes only, since DTOs legitimately reuse persistence-layer enums like `TicketStatus`). **Integration tests written** (`TicketVisibilityIT`, `TicketApiSecurityIT` — cross-team 404, untriaged 404, own-team 200, lead-sees-all, list excludes invisible tickets; `TicketNPlusOneIT` — Hibernate statement-count assertion) but **not yet CI-verified** — awaiting push.
- **Batch 4: ✅ VERIFIED GREEN IN CI.** All 71 tests pass on the runner, including `TicketVisibilityIT` (5), `TicketApiSecurityIT` (4), `TicketNPlusOneIT` (1), `ArchitectureRulesTest` (7).
- **Two test-isolation bugs found and fixed during Batch 4 CI verification** (test bugs, not application bugs — BR-A10 itself was never broken):
  1. `TicketApiSecurityIT.listExcludesInvisibleTickets` initially shared the seeded `billing-ops`/`platform-support` teams with other test methods in the same class; `@SpringBootTest` doesn't roll back between methods, so tickets written by one method leaked into another's count assertion. Fixed by giving that one test its own uniquely-named teams.
  2. That fix introduced a second-order bug: the extra teams it now inserts leaked into `AuthFlowIT.seedProducesExpectedTaxonomy` and `SeedIdempotencyIT.seedScriptIsIdempotent` — both asserted a bare `count(*)` against `teams`/`categories`, and `@SpringBootTest` shares one database across **all test classes** in a JVM fork, not just methods within a class. Fixed by: (a) `AuthFlowIT` now asserts the 5/10 *specific seeded slugs* exist rather than a bare count; (b) `SeedIdempotencyIT` now asserts the count doesn't **grow** after replaying the seed (before/after delta) rather than equals a fixed number — which is the actually-correct way to express "idempotent," independent of what other tests have inserted.
  - **Lesson recorded for future batches:** any test asserting a bare `count(*)` against a shared table (`teams`, `categories`, `users`) is fragile the moment any other test in the suite legitimately inserts a row there. Prefer asserting specific rows/slugs exist, or before/after deltas, never a fixed total.
- **Batch 5 (async ingestion — persist-first, executor, sweep, reaper, orphan fallback): ✅ VERIFIED GREEN IN CI.** Local unit/slice suite remains 39/39 green; the Testcontainers integration suite verified the full async/durability path on GitHub Actions.
  - `POST /api/tickets` — 202 (new) or 200 (BR-A02 duplicate), never 500/503. `TicketInsertAttempt` runs the insert in its OWN `REQUIRES_NEW` transaction — required because catching `DataIntegrityViolationException` in the SAME transaction that threw it leaves that transaction rollback-only, poisoning any subsequent read (a real bug caught and fixed during this batch, before any test even ran).
  - `TicketIngestionWorker` — the actual `@Async("ingestionExecutor")` worker, a separate bean from `TicketIngestionService` (self-invocation would bypass the `@Async` proxy).
  - `AsyncConfig` — `AbortPolicy` (never `CallerRunsPolicy`), MDC+SecurityContext-propagating `TaskDecorator`, distinct 2-thread `ThreadPoolTaskScheduler` so sweep/reaper don't block each other.
  - `TicketRepository.claimForProcessing` — the conditional-update claim (`WHERE ingestion_state='PENDING'`, bumps `attempt_count`), `@Transactional` directly on the repository method.
  - `IngestionSweepService` — sweep (60s, claims+redispatches PENDING) and reap (60s, resets stale PROCESSING → PENDING, or routes exhausted rows to `OrphanTicketService`).
  - `OrphanTicketService` — routes attempt-cap-exhausted tickets to the `customer-success` fallback team; **never leaves a FAILED row with `team_id NULL`**.
  - `IngestionUncaughtExceptionHandler` — moved to delegate into `TicketIngestionWorker.recordFailure` rather than touching `TicketRepository` directly, after the ArchUnit rule caught it reaching across the `ticket.app` boundary from `common.config`.
  - **6 required integration tests CI-verified:** `IngestionClaimConcurrencyIT` (20 concurrent threads, sum of claimed rows == 1), `TicketIngestionAfterCommitIT` (separate JDBC connection sees committed work), `IngestionRejectionIT` (real `core=1/max=1/queue=1` executor + `CountDownLatch`-blocked task, proves 202+PENDING+counter increment+later-DONE on genuine saturation), `IngestionReaperIT` (5 cases: stale-reset, fresh-untouched, PENDING/DONE-untouched, null-claimedAt-untouched, exhausted→fallback-team), `TicketIdempotencyConcurrencyIT` (10 concurrent authenticated POSTs, same externalRef, exactly 1 row + no 500s).
  - Added `awaitility` test dependency for polling assertions.
- **Batch 5 CI repair verified:** the sweep originally claimed a PENDING ticket before submitting it to the executor. During deliberate saturation that submission was rejected, leaving the ticket stranded in PROCESSING and causing `IngestionRejectionIT` to time out. Fixed by making the worker exclusively own the conditional claim; the sweep only submits bounded PENDING IDs. A rejected submission therefore leaves the ticket PENDING, increments `ingestion.queue.rejected`, and allows a later sweep to recover it. CI now proves this behaviour.
- **Batch 6 (final A1 pre-screen, prompt foundation, ADR/README hardening): code complete, 58/58 local tests green. NOT YET CI-verified.**
  - `PreScreenMatcher` is pure (zero Spring/JPA imports, covered by existing ArchUnit domain-purity rules), constructed only from YAML-bound `supportsense.pre-screen.terms`. It precompiles case-insensitive, Unicode-aware whole-word patterns once at construction; multi-word terms permit variable whitespace/newlines.
  - `AutoAnswerGate` combines pre-screen and category policy as an **OR** gate. A pre-screen hit produces the existing schema reason `KEYWORD_PRESCREEN`; a category-only block produces `SENSITIVE_CATEGORY`. No unapproved `SENSITIVE_TOPIC` value or V4 migration was added.
  - `continueOnlyWhenAllowed` has a test whose downstream continuation deliberately throws: a pre-screen match returns the human-route value before any later (A2 LLM) continuation can execute.
  - `PromptResourceLoader` loads versioned `.st` files from `resources/prompts/`; `triage-v1.st` establishes the BR-A12 classpath-resource foundation without an inline Java prompt.
  - Tests: `PreScreenMatcherTest` (11: substring-inside-word false positives, punctuation, hyphenation, case/Unicode, possessives, variable whitespace, null/empty/50KB safety, precompiled-pattern identity, malformed configuration), `AutoAnswerGateTest` (6: all OR combinations plus downstream short-circuit), `PromptResourceLoaderTest` (2: classpath load/render and missing template).
  - ADR-0013 and ADR-0014 are written and linked in the ADR index; ADR-0002 remains marked `Superseded` by ADR-0015, never deleted.
  - README now has architecture diagram, problem statement, run paths, business-rule→test map, ADR link, and explicit A2-only scope. Public-doc/code/config fixture scan found no employer name, internal hostname, CDSID, corporate email, or proxy address.
  - **Coverage note:** local unit-only report was 19.44% (145/746 lines) — not the CI gate's merged figure. No JaCoCo threshold/exclusion was weakened and no getter/setter padding was added.
- **Batch 6: ✅ CI GREEN.** GitHub Actions confirmed passing — build, full Surefire+Failsafe suite, and the JaCoCo merged-coverage check (enforced ≥60%) all succeeded. **Exact test counts and the precise merged coverage percentage were not captured from the run** — user confirmed success without pasting the log, so this checkpoint records pass/fail status only, not fabricated numbers. If exact figures are needed later (e.g. for a resume metric per sheet 16), re-run CI and capture the JaCoCo summary and Surefire/Failsafe totals from the Actions log.
- Mandatory pause points (user-specified): ✅ V1–V4/entities batch — reviewed and passed. ✅ Batch 4 (team-isolation/security) — CI-verified, closed. ✅ Batch 5 (ingestion durability) — CI-verified, closed. ✅ Batch 6 (final A1 hardening) — CI-verified, closed.

## Phase 4: Code Review

- **Dual-model blind review completed** (`blueprint-code-review` + `blueprint-code-review-altmodel`, run in parallel with no cross-visibility). Both independently reached REQUEST CHANGES on different grounds; every divergent claim was verified directly against source before acting — see the full comparison/divergence tables presented to the user in-session.
- **3 Blockers found (union of both reviews, no overlap) — all fixed and CI-verified:**
  1. `IngestionSweepService.reap()` ran the entire stale-ticket batch in one `@Transactional` method; one failure rolled back unrelated tickets' already-applied fixes. Fixed via a new `ReaperItemProcessor` bean (`@Transactional(REQUIRES_NEW)`, invoked through its own proxy, never self-invoked) called per-item inside a try/catch in `reap()`. Proven by `IngestionSweepServiceTest` (unit, mocked) and `IngestionReaperIT.oneFailingTicketDoesNotRollBackUnrelatedTicketsInTheSameBatch` (Testcontainers — temporarily breaks the fallback team to force a real failure, asserts the other two tickets still reached PENDING).
  2. `untriagedOrphanThreshold` config existed but nothing read it — a NEW ticket with `team_id NULL` that never got claimed could sit invisible indefinitely under BR-A10. Fixed with a new disjoint query/scheduler (`status=NEW AND ingestion_state=PENDING AND claimed_at IS NULL AND team IS NULL AND created_at < cutoff`) — cannot overlap with the existing PROCESSING+claimed_at-not-null reaper predicate. Same 3-attempt cap and fallback-team routing. Uses the injected `TimeSource`. Proven by `IngestionSweepServiceTest` (clock/threshold correctness, per-item isolation).
  3. `POST /api/tickets/bulk` did not exist at all, despite being required by requirements/execution plan. Implemented: max 500 enforced with 400 (not silent truncation), each item independently isolated via the existing `TicketIngestionService.ingest()` → `TicketInsertAttempt` REQUIRES_NEW path, honest per-item accepted/duplicate/rejected outcome array. Proven by `TicketBulkIngestionIT` (Testcontainers — real duplicate + real accept in one batch; 501-item batch rejected with zero rows written).
- **Bonus finding during my own re-sweep:** a 4th self-invocation instance (`OrphanTicketService.routeNeverClaimedOrphan` calling `this::routeToFallbackTeam`) introduced while fixing Blocker 2 — accidentally harmless (an active REQUIRES_NEW transaction papered over it) but refactored to a shared private method so it's correct by construction, not by coincidence.
- **CI status:** ✅ Green — all 3 Blocker fixes and their regression tests (including the two Testcontainers-only tests) verified on GitHub Actions.
- **6 Should-fix items — user disposition PENDING, not yet started:**
  1. `@PreAuthorize` inert — no `@EnableMethodSecurity` registered anywhere (found by primary only)
  2. JaCoCo `**/config/**` exclusion sweeps up real logic (`RequiredSecretsValidator`, `IngestionUncaughtExceptionHandler`) — both reviews agree
  3. JaCoCo `**/dto/**` exclusion is dead config (no `dto` package exists) — found by primary only
  4. JWT algorithm pinning asserted by reasoning, never proven by a test (`alg:none`/algorithm-confusion) — both reviews flagged, disagreed on severity (Nice-to-have vs Should-fix); user's proposed disposition treats it as Should-fix
  5. No negative test proving the AFTER_COMMIT listener does NOT fire on a rolled-back ticket-creation transaction — found by alt-model only
  6. Literal test-only secrets in `application-test.yml`/`application-local.yml` — both reviews flagged as benign; user's proposed disposition is defer/won't-fix (keep as clearly-fake fixtures)
- **User-approved disposition table presented in-session** (severity rationale, proposed fix, blast radius, recommendation per item) — awaiting explicit go-ahead to implement items 1–5; item 6 tentatively deferred.
- **Note on `@PreAuthorize("hasAnyRole('ADMIN','SERVICE'))")` added to the new bulk endpoint:** this annotation is currently inert for the same reason as SHF-001 — it will only take effect once `@EnableMethodSecurity` (Should-fix #1) is implemented. Today the endpoint is protected only by the HTTP filter chain's `.anyRequest().authenticated()` (any authenticated user, not role-restricted). This must be revisited when Should-fix #1 lands.
- **Should-fix remediation (items 1–5) is code-complete and awaiting CI verification; item 6 deferred by user:**
  1. `@EnableMethodSecurity` added to the component-scanned, unprofiled `SecurityConfig`, making all three previously-inert annotations active: `TicketService.listVisibleTickets` (`isAuthenticated()`), `TicketService.getVisibleTicket` (`isAuthenticated()`), and `TicketController.createBulk` (`ADMIN`/`SERVICE` only). `TicketMethodSecurityIT` is a full-context test (not a blind WebMvc slice): it asserts a JWT-authenticated AGENT receives 403 for bulk while an ADMIN role obtains 202 after re-login to obtain a new ADMIN-claim JWT. `TicketBulkIngestionIT` was also updated to use an ADMIN JWT, since its old self-registered AGENT flow legitimately becomes forbidden once enforcement activates.
  2. JaCoCo blanket `**/config/**` exclusion narrowed to only bean-wiring configuration classes plus the properties data record. Logic-bearing `RequiredSecretsValidator`, `IngestionUncaughtExceptionHandler`, and `MissingRequiredSecretException` are no longer excluded.
  3. Dead `**/dto/**` JaCoCo exclusion removed; no `dto` package exists. No `web` request/response records were added as replacements, avoiding a new broad exclusion.
  4. JWT generation explicitly pins `Jwts.SIG.HS256`. `JwtAlgorithmSecurityTest` proves `alg:none`, wrong-key HMAC, tampered-signature, and expired-token rejection against the real parser (4/4 local). Note: forged-token rejection also held before this explicit generation-policy hardening because typed `verifyWith(SecretKey)` already rejects unsigned/wrong-key tokens; a test that claims to fail before this particular change would be a dishonest structural/padding test. The new test closes the evidence gap identified by review rather than claiming a pre-existing vulnerability.
  5. `TicketDispatchRollbackIT` added: it uses a mocked `IngestionDispatchPort`, successfully inserts once to prove the AFTER_COMMIT listener works, resets the mock, then triggers a duplicate-key rollback and asserts no dispatch interaction occurs. CI-only due Testcontainers.
  6. Literal local/test fixture credentials intentionally deferred: README now tracks the A2 follow-up and its rationale (deterministic non-production fixtures; never deploy/reuse them).
- **Additional correctness fix during remediation:** bulk item validation was initially performed by Spring MVC's `List<@Valid ...>` before the controller ran, making an in-batch `REJECTED` outcome unreachable for a malformed item. Extracted `BulkIngestionService` validates each item inside the loop. `TicketBulkIngestionIT` now verifies ACCEPTED + DUPLICATE + REJECTED together and a 501-item request gets 400 with zero writes.
- **Local verification after remediation:** `mvn clean test` ✅ 65/65. The newly added full-context/Testcontainers tests (`TicketMethodSecurityIT`, `TicketDispatchRollbackIT`, updated `TicketBulkIngestionIT`) have not run locally because Docker is unavailable; CI is the required authority.
- **Final CI failure found and fixed (2026-09-05):** `AuthFlowIT.seedProducesExpectedTaxonomy` asserted a bare `count(*) FROM users WHERE role='ADMIN' = 1`. Once `@EnableMethodSecurity` landed, `TicketMethodSecurityIT` legitimately promotes fixture users to `ADMIN`, so the count became order-dependent (expected 1, was 4). Fixed by asserting the **specific seeded bootstrap admin** (`admin@supportsense.local` with role `ADMIN`) exists — the actual seed invariant — rather than requiring it to remain the only `ADMIN` row in a database shared by every integration-test class. **This is the third occurrence of the shared-database bare-`count(*)` anti-pattern in this project** (Batch 4 teams, Batch 4 categories, now users); see the recurring-lesson section of the review summary.
- **Phase 4 CLOSED: ✅ CI GREEN on GitHub Actions** — full Surefire + Failsafe suite and the JaCoCo merged-coverage gate (≥60%) all passed. Exact test totals and the precise merged coverage percentage were **not captured** from this run (user confirmed success without pasting the log), so no numbers are recorded here rather than inventing them. Re-run CI and capture the JaCoCo summary + Surefire/Failsafe totals if exact figures are needed for a resume metric (sheet 16).
- Hard stops (never do): `@Disabled`/`@Ignore`; lower JaCoCo threshold or add exclusions to pass it; substitute H2/embedded DB for Testcontainers; delete/weaken an ArchUnit rule; edit an AC to match the code.
- End-of-batch report format: batch name · business rules/ADRs touched · named test covering each · CI/local-test status · anything deferred.
- **Process note for future batches:** local `mvn test` passing is necessary but not sufficient — `*IT.java` (Testcontainers) tests only prove out on a real CI run. Push after every batch and wait for the Actions result before treating a batch as done.

## Phase 5: E2E Testing

- **Mode:** dual-model blind black-box journey-layer suite (`e2e`/`e2ealt` packages), HTTP-only, no repository/EntityManager/bean access permitted or used.
- **Scope narrowed deliberately, not by omission:** 4 of 6 originally-mandated journeys (triage→team→lifecycle happy path, true cross-team setup, abstention/sensitive-topic routing, fallback-team recovery) are genuinely unassertable black-box in A1 — no triage exists, no lifecycle endpoint exists, `AutoAnswerGate` has no production caller, and recovery needs a 15-min stale threshold plus hardcoded 60s scheduler intervals. Recorded as API-F1–F5 findings in README.md rather than worked around.
- **One narrow production change made, justified independent of testing:** `ingestionState` added as a read-only field on `TicketResponse` — a 202 response with no way to observe the accepted work's outcome is an incomplete API contract on its own merits. Documented in the new `api/src/main/resources/openapi.yaml` (first committed OpenAPI spec — previously inferred-only) and covered by a contract-drift test in both suites. `GET /api/teams` explicitly NOT built (API-F5) per user instruction — no product changes to satisfy a test.
- **Real defect found independently by both blind models, verified against source before acting:** every unauthenticated/invalid-credential request (missing/expired/tampered/`alg:none` token) returned 403 instead of 401 — `SecurityConfig` registered no `AuthenticationEntryPoint`/`AccessDeniedHandler`, so Spring Security's default applied uniformly to "no credentials" and "wrong role." Fixed with an explicit entry point (401) + access-denied handler (403) sharing one `SecurityResponseWriter`, both rendering the same `GlobalExceptionHandler` ProblemDetail shape. Regression-covered by `JwtRejectionStatusCodeIT`, including a same-body-across-failure-modes assertion (no credential-probing oracle). Full `SecurityConfig` re-audit also surfaced API-F6 (CORS unconfigured — harmless until A6) and API-F7 (no login rate-limiting — never in A1 scope).
- **Two test bugs found on the first real CI run, both fixed — neither was a production regression:** (1) `allRejectionModesReturnIdenticalBodies` compared raw JSON including the real-wall-clock `timestamp` field — now strips it before comparing; (2) `TicketIdempotencyJourneyAltIT`'s concurrent-idempotency follow-up read used the creator's own AGENT token, which can never see its own untriaged team-less ticket (API-F1 surfacing as a test bug) — now uses the seeded ADMIN token. Swept both suites for the same pattern elsewhere; none found.
- **Flake detection:** CI now runs the Failsafe suite twice per job, second run to a separate reports directory (`target/failsafe-reports-rerun`) so it never clobbers the primary JaCoCo merge or test-reporter input. No flake observed after the fixes.
- **Phase 5 CLOSED: ✅ CI GREEN on GitHub Actions**, both blind suites plus the 401/403 regression test passing. Exact test totals for this specific green run were not captured from a pasted log; test summary records pass/fail status and content, not invented numbers.

## Phase 6: Security

- **Mode:** dual-model blind static/manual audit, same protocol as Phase 5 — no automated scanners, both models read source independently with no cross-visibility.
- **Convergence:** both models reached near-identical verdicts on JWT/session security, `SecurityConfig` line-by-line, injection/data-exposure, and secrets/history — zero real secrets in working tree or full 23-commit git history, no SQL/JPQL injection anywhere, 401 body confirmed identical across all rejection modes (no credential-probing oracle), refresh-token family revocation confirmed irreversible.
- **One material divergence, verified before acting:** Model A found a real authorization gap — unauthenticated `POST /api/auth/register` accepted a client-supplied `teamId` with no validation, letting any anonymous caller self-assign into an arbitrary team and inherit its ticket visibility via `TicketSpecifications.visibleTo`. Model B missed this entirely. Verified myself: confirmed `RegisterRequest` has no `role` field (no privilege-escalation path), swept every other request DTO for the same mass-assignment pattern (none found), confirmed via git history no prior incident occurred, and confirmed the narrow current blast radius (no A1 code assigns non-null teams except the orphan fallback) is an artifact of A1 lacking triage — not a property of the defect, since A2 triage would expose every ticket the moment it starts assigning teams.
- **Fixed:** `RegisterRequest` gained a `@AssertTrue` constraint rejecting any non-null `teamId` with 400. Regression-tested by `RegistrationTeamAssignmentSecurityIT` (rejects with no user row created; null teamId still succeeds). `TicketApiSecurityIT`'s team-scoped test fixtures updated to assign team via SQL post-registration instead of the now-rejected field (re-login not needed — confirmed `TicketService` re-reads team membership from the DB per request, not from JWT claims).
- **False positive caught and corrected:** Model B flagged missing `@Valid` on the bulk endpoint's request list. Verified against `BulkIngestionService.ingestItem()` — manual per-item validation is a deliberate Phase 4 design choice (so a malformed item produces REJECTED instead of failing the whole batch), not a gap. Removed from findings.
- **Process note logged in the security review:** single-model coverage on the authorization surface is not sufficient — the one real gap was found by only one of two independent reviewers, and neither model's own severity assessment traced the actual current blast radius before rating it. Recorded as a review-method limitation, not just a code finding.
- **All other findings Low/Informational**, correctly scoped: no rate-limiting on login (Medium, pre-existing, recorded as API-F7, not fixed this phase — genuinely unscoped for A1), no CORS config (Low, API-F6, immaterial until A6), no `iss`/`aud` claims (Low, immaterial until a second token-consuming service exists), no JWT clock-skew tolerance (Low), actuator role-restriction lacks an explicit guard test (Informational).
- **A2 forward-looking security concerns recorded as requirements input** (not designed): prompt injection into the classifier via ticket body, customer PII crossing to a third-party model provider, unbounded LLM cost as a denial-of-wallet vector, untrusted model output being persisted/rendered. Written to `.github/artifactory/supportsense-a2-security-input.md` so A2 Phase 1 starts with these known rather than rediscovering them.
- **Local verification:** `mvn clean test` ✅ 65/65. `*IT.java` (including the new `RegistrationTeamAssignmentSecurityIT` and the updated `TicketApiSecurityIT`) requires the next CI push — not yet verified on GitHub Actions.
- **Phase 6 CLOSED pending CI**: security review, A1 close-out table (business rule → test, ADR list, deferred items), and A2 security input all written and verified. Merged coverage % not captured this phase (no log pasted) — consistent with project convention of not inventing numbers.
