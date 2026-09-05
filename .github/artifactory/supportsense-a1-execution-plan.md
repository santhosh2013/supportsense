# Execution Plan — SupportSense Milestone A1

**Slug:** `supportsense-a1`
**Design:** [.github/artifactory/supportsense-a1-design.md](supportsense-a1-design.md)
**Date:** 2026-09-05

Seven batches. Each ends with a verification step. Batch 1 establishes CI first so nothing
is written that cannot be verified somewhere.

**One batch = one PR.** Conventional commit messages. **No squashing batches together** —
the commit history is read during a code walkthrough and must show deliberate, reviewable
increments.

---

## Batch 1 — Walking skeleton, CI, ADR index

**Goal:** A repository proven green in CI — including a **real Testcontainers run** —
before any business code exists.

> Batch 1 is the one batch where omissions are invisible and permanent. Everything below
> that is deferred becomes retrofitting against dozens of files, at which point the reflex
> is to weaken the rule rather than fix the code.

| # | Deliverable |
|---|---|
| 1.1 | Repo layout: `api/`, `infra/`, `docs/`, `eval/`, `.github/workflows/`, `.gitignore`, `README.md` |
| 1.2 | `api/pom.xml` — Spring Boot 3.3.x pinned, Java 21, only sheet-03 dependencies |
| 1.3 | `SupportSenseApplication`, `application.yml` + `application-{local,test,cloud}.yml` |
| 1.4 | Fail-fast config validator: `SUPPORTSENSE_JWT_SECRET`, `SUPPORTSENSE_ADMIN_PASSWORD` required outside `local` |
| 1.5 | `infra/docker-compose.yml` — `pgvector/pgvector:pg16` + Redis 7 |
| 1.6 | **Walking-skeleton IT** — starts `pgvector/pgvector:pg16` via Testcontainers, applies a baseline Flyway migration, asserts `/actuator/health` returns `UP`. **Must run green on the GitHub Actions runner.** Since Docker cannot run locally, "CI green" is *demonstrated*, not assumed. |
| 1.7 | Surefire/Failsafe split by `@Tag("integration")` + `*IT.java` |
| 1.8 | **JaCoCo merged report — configured now, not later:** `prepare-agent` + `prepare-agent-integration` → `merge` → `report` → `check` against **merged** data. Exclusions: `**/config/**`, DTO records, generated code, `SupportSenseApplication`. <br>*Deferring this makes the 60 % gate fail spuriously, and the reflex is to lower the threshold.* |
| 1.9 | **ArchUnit in place from the start** — pure-domain classes have **zero** Spring and **zero** JPA imports; no controller imports a repository; no inline `Instant.now()`. <br>*Architecture C is only real if enforced. Added later, it fails on 40 files at once and gets deleted.* |
| 1.10 | `.github/workflows/ci.yml` — `fetch-depth: 0`, `cache: maven`, gitleaks (full history), `mvn verify`, JaCoCo merged check, test-reporter on failure |
| 1.11 | **`docs/adr/README.md` index + one file per decision already made:** ADR-0001 (tier snapshot), 0002 (`AbortPolicy` over `CallerRunsPolicy` — **marked `Superseded` by 0015**), 0003 (dual providers), 0005 (deferred API-key auth), 0006 (404 over 403), 0007 (Testcontainers CI-first), 0008 (Neon), 0009 (pre-screen independent of LLM), 0010 (package by feature), 0011 (persist-then-dispatch + sweep/reaper), 0013 (pure domain core), **0015 (202 + counter — supersedes 0002)**. <br>*Written as implemented, never retrospectively.* |
| 1.12 | `docs/metrics.md` — false-deflection formula written **before** A6 |

**Verify:**
- `mvn test` passes locally with **no Docker** (unit + ArchUnit only)
- `mvn verify` green **in CI**, with the walking-skeleton Testcontainers test actually executing
- JaCoCo produces a **merged** report covering both runs
- gitleaks clean over full history
- ADR index lists every file and no link is broken

**Commit:** `chore: walking skeleton with CI, merged coverage, ArchUnit and ADR index`

---

## Batch 2 — V1/V2 migrations, core entities, auth

| # | Deliverable |
|---|---|
| 2.1 | `V1__init.sql` — `users`, `teams`, `categories` (incl. `auto_answer_blocked`), `refresh_tokens` + indexes |
| 2.2 | `V2__seed_taxonomy.sql` — 5 teams, 10 categories, 1 ADMIN; **idempotent upsert on slug / `lower(email)`** |
| 2.3 | Entities: `User` (roles incl. `SERVICE`), `Team`, `Category`, `RefreshToken` |
| 2.4 | `common/domain/Clock` abstraction wired as a bean |
| 2.5 | `common/web/GlobalExceptionHandler` — RFC-7807 `ProblemDetail`, single `@ControllerAdvice` |
| 2.6 | Spring Security: stateless, BCrypt(10), HS256, `@PreAuthorize` at service layer |
| 2.7 | `AuthService` + `RefreshTokenService` — SHA-256 hashing, rotation, **family revocation on reuse** |
| 2.8 | `AuthController` — register / login / me |
| 2.9 | springdoc-openapi at `/swagger-ui.html` |
| 2.10 | ADR-0004 (DB invariants) |

**Verify:** Flyway applies cleanly; seed idempotency IT; token-theft IT (replay ⇒ 401 + family revoked); login never reveals email existence; `@WebMvcTest` asserts `ProblemDetail` schema.

**Commit:** `feat(auth): V1/V2 migrations, core entities, JWT with rotating refresh tokens`

---

## Batch 3 — V3/V4 migrations, ticket entities

| # | Deliverable |
|---|---|
| 3.1 | `V3__tickets.sql` — `tickets` + `ticket_events`, all indexes incl. `ix_event_reopen` (predicate **excluding** `RESOLVED→CLOSED`) and `ix_ticket_ingestion_pending` |
| 3.2 | `tickets` extra columns: `customer_tier`, `auto_answered`, `auto_answered_at`, `first_resolved_at`, `ingestion_state`, `claimed_at`, `attempt_count`, `ingestion_error`, `version` |
| 3.3 | `V4__triage.sql` — `triage_results` (`numeric(4,3)` + CHECKs, `abstention_reason`), `duplicate_links` (canonical `ticket_a_id < ticket_b_id` CHECK + UNIQUE) |
| 3.4 | Entities: `Ticket`, `TicketEvent` (full) |
| 3.5 | Entities: `TriageResult`, `DuplicateLink` — **persistence-only**, bare `JpaRepository`, no services, no endpoints |
| 3.6 | ArchUnit rule: nothing in `..app..`/`..web..` may reference `TriageResult` or `DuplicateLink` in A1 |

**Verify:** `ddl-auto=validate` clean (Flyway drift gate); `@DataJpaTest` proves canonical-pair CHECK rejects `a > b` and UNIQUE rejects the reversed pair; confidence CHECK rejects 1.001.

**Commit:** `feat(ticket): V3/V4 migrations and ticket entities`

---

## Batch 4 — Ticket lifecycle & visibility

| # | Deliverable |
|---|---|
| 4.1 | `TicketStatusTransitions` — **pure**, zero Spring/JPA imports |
| 4.2 | Illegal transition ⇒ 409 `ProblemDetail` **listing allowed target statuses** |
| 4.3 | `TicketSpecifications.visibleTo(principal)` — the single BR-A10 fragment |
| 4.4 | `TicketRepository` finders — every one composes the visibility predicate |
| 4.5 | ArchUnit rule: no ticket finder bypasses the predicate |
| 4.6 | `TicketService` — `@PreAuthorize`, `@EntityGraph` on list queries |
| 4.7 | `TicketController` — GET list (paginated, max 100), GET by id |
| 4.8 | ADR-0012 (`ingestion_state` separate from business `status`) |

**Verify:** parameterised test over **every** legal and illegal transition pair; security ITs — agent↔own team 200, cross-team 404, untriaged 404, lead untriaged 200, untriaged absent from agent list **and total count**; statement-count assertion proves no N+1.

**Commit:** `feat(ticket): status transition machine and BR-A10 visibility predicate`

---

## Batch 5 — Async ingestion

| # | Deliverable |
|---|---|
| 5.1 | `AsyncConfig` — core 4 / max 8 / queue 500, **`AbortPolicy`** |
| 5.2 | `TaskDecorator` propagating MDC trace ID + `SecurityContext` |
| 5.3 | `AsyncUncaughtExceptionHandler` → `ingestion_state = FAILED`, error persisted |
| 5.4 | `TicketIngestionService` — persist first, `@TransactionalEventListener(AFTER_COMMIT)` dispatch |
| 5.5 | Rejection handler — **202 + `ingestion.queue.rejected`**, state stays `PENDING` (supersedes 503) |
| 5.6 | BR-A02 — catch unique violation ⇒ 200 + existing ticket |
| 5.7 | `POST /api/tickets` (202) and `POST /api/tickets/bulk` (ADMIN, max 500, partial success) |
| 5.8 | Confirm ADR-0002 is marked `Superseded` and ADR-0015 reflects the shipped behaviour |

**Verify:** 5000-word ticket ⇒ 202 in **< 400 ms**; same `externalRef` **3× ⇒ exactly 1 row**; saturated queue ⇒ AC-6's **four** assertions (202, persisted `NEW`/`PENDING`, counter incremented, sweep processes it); dispatch confirmed to fire after commit.

**Commit:** `feat(ticket): persist-first async ingestion with idempotent externalRef`

---

## Batch 6 — Sweep, reaper, orphan prevention

| # | Deliverable |
|---|---|
| 6.1 | Sweep (60 s + startup) — conditional-update claim, proceed only if 1 row affected |
| 6.2 | Reaper (60 s) — `PROCESSING` older than 15 min ⇒ `PENDING`; `attempt_count >= 3` ⇒ `FAILED` |
| 6.3 | Orphan prevention — 3 failures **or** `NEW` > 1 h ⇒ fallback team **Customer Success**, `abstention_reason = TOOL_FAILURE` |
| 6.4 | Micrometer counters/gauges from design §8 |

**Verify:** two concurrent claim attempts ⇒ exactly one wins; stale `PROCESSING` row reset; 4th attempt ⇒ `FAILED`; orphaned ticket assigned to fallback team.

**Commit:** `feat(ticket): ingestion sweep, reaper and orphan prevention`

---

## Batch 7 — Pre-screen config, docs, hardening

| # | Deliverable |
|---|---|
| 7.1 | `supportsense.prescreen.terms` config (4 groups) + `PreScreenMatcher` — **pure**, case-insensitive **word-boundary** matching |
| 7.2 | `resources/prompts/` structure + loading test (BR-A12 foundation) |
| 7.3 | ADR-0014 (merged JaCoCo) |
| 7.4 | `README.md` — Neon setup, no-Docker path, proxy notes, architecture diagram |
| 7.5 | Full ADR index cross-check — all 15 present, correctly linked, statuses accurate |
| 7.6 | Coverage gap closure to clear the 60 % merged floor |

**Verify:** `PreScreenMatcher` unit test proves `issue` does **not** match `sue` while `lawsuit` **does** match `lawsuit`; all 14 ACs pass; CI fully green.

**Commit:** `feat(triage): configurable pre-screen matcher and A1 documentation`

---

## Acceptance criteria coverage

| AC | Batch |
|---|---|
| AC-1 Flyway applies cleanly | 2, 3 |
| AC-2 `ddl-auto=validate` no drift | 3 |
| AC-3 Ingest 200 seed tickets | 5 |
| AC-4 3× `externalRef` ⇒ 1 row | 5 |
| AC-5 < 400 ms ingestion | 5 |
| AC-6 Queue saturation | 5 — 202 + counter + sweep recovery (4 assertions) |
| AC-7 Transition rules | 4 |
| AC-8 Cross-team 404 | 4 |
| AC-9 `ProblemDetail` everywhere | 2 |
| AC-10 Swagger live | 2 |
| AC-11 CI green | 1, 7 |
| AC-12 Local tests, no Docker | 1 |
| AC-13 Seed idempotent | 2 |
| AC-14 Fail-fast on missing env var | 1 |

---

## Risks

| Risk | Mitigation |
|---|---|
| Scheduled sweep/reaper double-firing across Cloud Run instances | Conditional-update claim makes both idempotent — no lock needed |
| JaCoCo merge misconfigured ⇒ false coverage failure | Batch 1 verifies merged reporting before real code exists |
| Testcontainers cannot run locally | CI-first; batch verification depends on CI, not the dev machine |
| ArchUnit rules too strict, blocking legitimate code | Rules added incrementally per batch, not all in batch 1 |
