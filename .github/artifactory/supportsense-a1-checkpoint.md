# Pipeline Checkpoint — supportsense-a1

## State
- Phase completed: 3 (Build — all 6 batches CI-verified green)
- Next phase: 4 (Code Review)
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
- Hard stops (never do): `@Disabled`/`@Ignore`; lower JaCoCo threshold or add exclusions to pass it; substitute H2/embedded DB for Testcontainers; delete/weaken an ArchUnit rule; edit an AC to match the code.
- End-of-batch report format: batch name · business rules/ADRs touched · named test covering each · CI/local-test status · anything deferred.
- **Process note for future batches:** local `mvn test` passing is necessary but not sufficient — `*IT.java` (Testcontainers) tests only prove out on a real CI run. Push after every batch and wait for the Actions result before treating a batch as done.
