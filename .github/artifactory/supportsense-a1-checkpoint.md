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
