# Pipeline Checkpoint — supportsense-a1

## State
- Phase completed: 2 (Design)
- Next phase: 3 (Build — Batch 1)
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
- Mandatory pause points (user-specified, still in force): ✅ V1–V4/entities batch — reviewed and passed. ✅ Batch 4 (team-isolation/security) — code complete, awaiting CI verification before treating as closed. Remaining: after the ingestion durability batch (persist-first, sweep, reaper, conditional claim — Batch 5).
- Hard stops (never do): `@Disabled`/`@Ignore`; lower JaCoCo threshold or add exclusions to pass it; substitute H2/embedded DB for Testcontainers; delete/weaken an ArchUnit rule; edit an AC to match the code.
- End-of-batch report format: batch name · business rules/ADRs touched · named test covering each · CI/local-test status · anything deferred.
- **Process note for future batches:** local `mvn test` passing is necessary but not sufficient — `*IT.java` (Testcontainers) tests only prove out on a real CI run. Push after every batch and wait for the Actions result before treating a batch as done.
