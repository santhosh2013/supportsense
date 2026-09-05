# Design — SupportSense Milestone A1

**Slug:** `supportsense-a1`
**Requirements:** [.github/artifactory/supportsense-a1-requirements.md](supportsense-a1-requirements.md)
**Date:** 2026-09-05
**Status:** Draft — awaiting approval

---

## 1. Architecture overview

**Pattern: feature-sliced modular monolith with a pure domain core** (Approach C).

JPA entities are the single persistence model. Invariant logic is extracted into
**pure, dependency-free classes** carrying **zero Spring and zero JPA imports**.
Interfaces exist only at genuine seams: AI provider, clock, ingestion dispatch.

The structure follows sheet 13's test strategy rather than a template — the logic sheet 13
demands be unit-tested "with no Spring context" (transition machines, threshold logic, RRF
fusion, chunker) is exactly the logic that gets extracted.

```
api/src/main/java/io/github/santhosh2013/supportsense/
  SupportSenseApplication.java
  common/
    domain/        Clock abstraction, IdGenerator, shared value types
    web/           GlobalExceptionHandler (@ControllerAdvice), ProblemDetail factory
    persistence/   BaseAuditEntity, converters
    config/        AsyncConfig, SecurityConfig, OpenApiConfig, MetricsConfig
  auth/
    web/           AuthController
    app/           AuthService, RefreshTokenService
    domain/        TokenFamily (pure)
    persistence/   User, RefreshToken, UserRepository, RefreshTokenRepository
  ticket/
    web/           TicketController
    app/           TicketService, TicketIngestionService, TicketVisibility
    domain/        TicketStatusTransitions (pure), IngestionDispatchPort
    persistence/   Ticket, TicketEvent, TicketRepository, TicketEventRepository
  triage/
    domain/        PreScreenMatcher (pure), AbstentionPolicy (pure)  [A2 logic]
    persistence/   TriageResult, DuplicateLink, + bare JpaRepository each
  kb/              [A3]
  retrieval/       [A4]
```

**Enforced by ArchUnit**, not convention:

| Rule | Assertion |
|---|---|
| Pure domain isolation | No class in `..domain..` may depend on `org.springframework..`, `jakarta.persistence..`, or `org.hibernate..` |
| No entity leakage | No class in `..web..` may reference a type in `..persistence..` |
| Layer direction | `persistence` must not depend on `web` or `app` |
| No inline time | No production class may call `Instant.now()`, `LocalDateTime.now()`, or `System.currentTimeMillis()` |

**`Clock` is injected everywhere.** SLA deadlines, the 15-minute reaper threshold, and the
48-hour false-deflection window are all time-dependent — inline `Instant.now()` makes them
untestable without sleeping.

---

## 2. Ingestion architecture (FR-4, FR-5, BR-A01, BR-A02)

### 2.1 The core decision: persist first, dispatch after commit

The ticket row **is** the work item — no separate job table.

```
POST /api/tickets
  │
  ├─ validate request                                    [sync]
  ├─ INSERT ticket (status=NEW, ingestion_state=PENDING) [sync, transactional]
  │    └─ unique violation on external_ref ─────────────► return 200 + existing ticket
  ├─ INSERT ticket_event (CREATED)                       [sync, same tx]
  │
  ├─ COMMIT ◄──── durability boundary
  │
  ├─ @TransactionalEventListener(AFTER_COMMIT)
  │    └─ executor.execute(...)  ── on RejectedExecutionException ──┐
  │                                                                  │
  │                                       increment ingestion.queue.rejected
  │                                       leave ingestion_state=PENDING
  │                                       (sweep will collect it)
  │
  └─ return 202 Accepted
```

**Dispatch fires `AFTER_COMMIT`, never inside the transaction.** Otherwise the worker can
start before the row is visible and read nothing.

### 2.2 Two state columns, deliberately separate

> **Design conflict resolved here.** The claim mechanism needs a `PROCESSING` state, but
> `PROCESSING` is **not** a member of `Ticket.status` (`NEW, TRIAGED, IN_PROGRESS,
> PENDING_CUSTOMER, RESOLVED, CLOSED, DUPLICATE`). Adding it would corrupt BR-A09's
> transition machine — every legal/illegal pair test would need to account for a state that
> has nothing to do with the support lifecycle.

`PROCESSING` is a **pipeline** state; `NEW`/`TRIAGED` are **business lifecycle** states.
They are orthogonal, so they get separate columns.

| Column | Values | Governed by |
|---|---|---|
| `status` | `NEW, TRIAGED, IN_PROGRESS, PENDING_CUSTOMER, RESOLVED, CLOSED, DUPLICATE` | BR-A09 transition machine; human/business actions |
| `ingestion_state` | `PENDING, PROCESSING, DONE, FAILED` | The async pipeline only |

Supporting columns: `claimed_at timestamptz NULL`, `attempt_count int NOT NULL DEFAULT 0`,
`ingestion_error text NULL`.

### 2.3 Concurrency-safe claim

Cloud Run runs multiple instances; two starting at once must not both claim the same row.
A conditional update is sufficient — **no distributed lock**.

```sql
UPDATE tickets
   SET ingestion_state = 'PROCESSING',
       claimed_at      = :now,
       attempt_count   = attempt_count + 1
 WHERE id = :id
   AND ingestion_state = 'PENDING';
```

Proceed **only if exactly one row was affected**. Zero rows ⇒ another instance won; return silently.

### 2.4 Sweep and reaper

Two scheduled jobs, both idempotent and safe to run concurrently across instances:

| Job | Interval | Action |
|---|---|---|
| **Sweep** | 60 s | Claim and dispatch tickets where `ingestion_state = 'PENDING'` (recovers restart-dropped and queue-rejected work) |
| **Reaper** | 60 s | `ingestion_state = 'PROCESSING' AND claimed_at < now() - interval '15 minutes'` → reset to `PENDING`. If `attempt_count >= 3` → `FAILED` with `ingestion_error` persisted. |

Without the reaper, a pod dying mid-processing leaves the row in `PROCESSING` forever and
the `PENDING`-only sweep never touches it. The attempt cap stops poison messages retrying
indefinitely.

Also runs at startup, so a restart recovers immediately rather than waiting a full interval.

### 2.5 Executor configuration

| Setting | Value |
|---|---|
| Core / max pool | 4 / 8 |
| Queue capacity | 500 |
| Rejection policy | **`AbortPolicy`** — never `CallerRunsPolicy` |
| Decorator | `TaskDecorator` copying **MDC trace ID** + **`SecurityContext`** |
| Uncaught handler | `AsyncUncaughtExceptionHandler` → `ingestion_state = FAILED`, error persisted |
| Servlet layer | Virtual threads enabled (sheet 03) |

### 2.6 Rejection behaviour — supersedes the requirements' 503

> **This reverses FR-5 as originally written.**

Because the ticket is **durably committed before dispatch**, a `RejectedExecutionException`
no longer loses data — the sweep collects it within 60 s. Returning **503 for work that is
already persisted would be a lie to the caller**, and would cause well-behaved clients to
retry, creating load the system does not need.

| Condition | Response | Side effect |
|---|---|---|
| Queue accepted the task | **202** | — |
| Queue rejected (`AbortPolicy` fired) | **202** | `ingestion.queue.rejected` counter incremented; `ingestion_state` stays `PENDING` |

`AbortPolicy` is retained — the point was never the status code, it was **refusing to let
the request thread execute the work**. `CallerRunsPolicy` would still silently convert
saturation into multi-second request latency and break BR-A01's sub-400 ms contract.

Recorded in **ADR-0002** including the reversal and its reasoning.

### 2.7 Idempotency (BR-A02)

Enforced by the database, not by a read-then-write check — a `SELECT` before `INSERT` is
racy under concurrent retries.

```
INSERT → catch DataIntegrityViolationException on ux_ticket_external_ref
       → SELECT existing by external_ref
       → return 200 with existing ticket
```

---

## 3. Security architecture (FR-3, BR-A10)

### 3.1 Authentication

| Element | Decision |
|---|---|
| Algorithm | HS256; signing key from env var, absent ⇒ **startup failure** |
| Access token | 15 min |
| Refresh token | 7 days, **SHA-256 hashed at rest**, rotated on every use |
| Theft detection | Reuse of a rotated token ⇒ **revoke the entire family** |
| Password | BCrypt strength 10 |
| Session | Stateless — no `HttpSession`, `SessionCreationPolicy.STATELESS` |
| Authorization | `@PreAuthorize` at the **service** layer, not the controller |
| Roles | `AGENT`, `LEAD`, `ADMIN`, `SERVICE` |

`refresh_tokens` table: `id`, `user_id`, `token_hash` (SHA-256, unique), `family_id`,
`issued_at`, `expires_at`, `rotated_at`, `revoked_at`.

### 3.2 Visibility predicate — one fragment, applied everywhere

BR-A10 is expressed **once** as a reusable predicate and applied in the repository layer to
**every** ticket read. Re-implementing it per query guarantees that some endpoint eventually
forgets it.

```
visible(ticket, principal) :=
      principal.role IN (LEAD, ADMIN)
   OR ticket.team_id = principal.teamId
```

Implemented as a single JPA `Specification` fragment (`TicketSpecifications.visibleTo(principal)`)
composed into every finder, plus an ArchUnit rule asserting no `TicketRepository` method is
called without it.

**Untriaged tickets (`team_id IS NULL`) are visible to `LEAD`/`ADMIN` only** — the predicate
above yields that naturally, with no special case.

| Actor | Target | Result |
|---|---|---|
| AGENT | own team's ticket | 200 |
| AGENT | another team's ticket | **404** |
| AGENT | untriaged (`team_id` NULL) | **404** |
| AGENT | list endpoint | untriaged tickets absent from **both results and total count** |
| LEAD / ADMIN | any ticket, incl. untriaged | 200 |

**404, never 403** — 403 confirms the resource exists and is an existence oracle (ADR-0006).

### 3.3 Orphan prevention

`team_id IS NULL` is a transient state measured in seconds — **never a resting state**.

| Trigger | Action |
|---|---|
| `attempt_count >= 3` (classification failed) | Assign fallback triage team **Customer Success**; `abstention_reason = TOOL_FAILURE` |
| `status = NEW` for **> 1 hour** | Same fallback assignment |

Metric `tickets.untriaged.age` (gauge) exposes tickets sitting untriaged beyond the threshold.

---

## 4. Data architecture

### 4.1 Migrations

| Migration | Contents |
|---|---|
| `V1__init.sql` | `users`, `teams`, `categories`, `refresh_tokens` |
| `V2__seed_taxonomy.sql` | 5 teams, 10 leaf categories, 1 ADMIN — idempotent upsert on slug / `lower(email)` |
| `V3__tickets.sql` | `tickets`, `ticket_events` |
| `V4__triage.sql` | `triage_results`, `duplicate_links` |

### 4.2 Key constraints — database-enforced invariants (ADR-0004)

| Invariant | Mechanism |
|---|---|
| Idempotent ingestion | `ux_ticket_external_ref UNIQUE (external_ref)` |
| Confidence is a probability | `numeric(4,3)` + `CHECK (x >= 0 AND x <= 1)` — **never float** |
| Duplicate pair uniqueness | `CHECK (ticket_a_id < ticket_b_id)` + `UNIQUE (ticket_a_id, ticket_b_id)` |
| Resolution ordering | `CHECK (resolved_at IS NULL OR resolved_at >= created_at)` |
| Optimistic locking | `version integer NOT NULL DEFAULT 0` |

A constraint holds under concurrency, retries, and future callers that bypass the service
layer. An application-level check does not.

### 4.3 Indexes

| Index | Definition |
|---|---|
| `ux_users_email_lower` | `UNIQUE ON lower(email)` |
| `ux_team_slug`, `ux_category_slug` | UNIQUE |
| `ix_category_parent` | `(parent_id)` |
| `ux_ticket_external_ref` | `UNIQUE (external_ref)` |
| `ix_ticket_status_sla` | `(status, sla_due_at) WHERE resolved_at IS NULL` |
| `ix_ticket_category_created` | `(category_id, created_at)` |
| `ix_ticket_ingestion_pending` | `(ingestion_state, claimed_at) WHERE ingestion_state IN ('PENDING','PROCESSING')` — keeps sweep and reaper cheap |
| `ix_event_ticket_occurred` | `(ticket_id, occurred_at DESC)` |
| `ix_event_reopen` | `(ticket_id, occurred_at) WHERE from_status IN ('RESOLVED','CLOSED') AND to_status NOT IN ('RESOLVED','CLOSED')` |
| `ix_triage_ticket`, `ix_triage_abstained` | `(ticket_id)`, `(abstained, created_at)` |

### 4.4 N+1 prevention

Every list endpoint uses `@EntityGraph` over `category`, `team`, `assignee`. A
`@DataJpaTest` asserts the **SQL statement count** to prove it.

---

## 5. Testing architecture

### 5.1 Split

Tags control execution; `*IT.java` naming keeps Failsafe's defaults working and makes intent
obvious in the file tree. **Both are used together.**

| Command | Runner | Includes | Docker |
|---|---|---|---|
| `mvn test` | Surefire | Unit + `@WebMvcTest` + ArchUnit | **No** — must pass locally |
| `mvn verify` | Surefire + Failsafe | Everything, incl. `@Tag("integration")` / `*IT.java` | Yes — CI only |

**No test is ever `@Disabled`. H2 is never used.**

### 5.2 JaCoCo merged coverage — the landmine

The default `prepare-agent` instruments only the unit-test run, so **integration-test
coverage is silently discarded** and the 60 % gate fails even with good coverage. The usual
reaction is to lower the threshold, which defeats the point.

Configuration:

1. `prepare-agent` → `target/jacoco-unit.exec`
2. `prepare-agent-integration` → `target/jacoco-it.exec`
3. `jacoco:merge` → `target/jacoco-merged.exec`
4. `report` + `check` applied to the **merged** data

Exclusions: generated code, `**/config/**`, DTO records, `SupportSenseApplication`.

### 5.3 Test inventory (A1)

| Layer | Tool | Covers |
|---|---|---|
| Unit | JUnit 5 + Mockito | `TicketStatusTransitions`, `PreScreenMatcher`, `TokenFamily`, SLA calc — no Spring context |
| Architecture | ArchUnit | Domain purity, no entity leakage, no inline `now()` |
| Slice | `@WebMvcTest` | Validation, `ProblemDetail` schema, role enforcement |
| Repository | `@DataJpaTest` + Testcontainers | Constraints, visibility predicate, statement counts |
| Integration | `@SpringBootTest` + Testcontainers | Auth flow, idempotency, async lifecycle, sweep/reaper |
| Migration | Testcontainers | Flyway drift (`ddl-auto=validate`), seed idempotency |

---

## 6. CI pipeline (FR-9)

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - checkout            # fetch-depth: 0  (gitleaks needs full history)
      - setup-java 21       # cache: maven
      - gitleaks            # full history scan
      - mvn verify          # Surefire + Failsafe; Testcontainers execute for real
      - flyway drift gate   # ddl-auto=validate against Testcontainer Postgres
      - jacoco merge+check  # 60% floor on MERGED data
      - test-reporter       # publish surefire+failsafe XML on failure
```

The test reporter matters more than usual: Testcontainers cannot run on the dev machine, so
a red build must be diagnosable **without** re-running locally.

---

## 7. Configuration & profiles

| Profile | Datasource | Chat | Embedding |
|---|---|---|---|
| `local` | Neon via `SPRING_DATASOURCE_URL` (`sslmode=require`) | Ollama `llama3.1` | Ollama `nomic-embed-text` (768) |
| `test` | Testcontainers `pgvector/pgvector:pg16` | mocked | mocked |
| `cloud` | Cloud SQL | Gemini `gemini-2.0-flash` | Gemini `text-embedding-004` (768) |

Business code depends **only** on `ChatModel` / `EmbeddingModel`. Identical 768 dimensions
mean provider switching needs **no migration**.

**Fail-fast at startup** when required env vars are absent outside `local`:
`SUPPORTSENSE_ADMIN_PASSWORD`, `SUPPORTSENSE_JWT_SECRET`. No default fallback, ever.

Pre-screen terms live under `supportsense.prescreen.terms`, matched
**case-insensitively with word boundaries (`\b`)** — substring matching produces false
positives such as `sue` inside `issue`.

---

## 8. Observability

| Metric | Type | Purpose |
|---|---|---|
| `ingestion.accepted` | counter | Tickets accepted |
| `ingestion.duplicate` | counter | BR-A02 idempotent hits |
| `ingestion.queue.rejected` | counter | Executor rejections (recovered by sweep) |
| `ingestion.sweep.recovered` | counter | Rows recovered by sweep |
| `ingestion.reaper.reset` | counter | Stuck rows reset |
| `ingestion.failed` | counter | Attempt cap exceeded |
| `ingestion.latency` | timer | Sync portion — BR-A01's < 400 ms |
| `tickets.untriaged.age` | gauge | Orphan detection |

Structured JSON logs with a trace ID on every request; the `TaskDecorator` carries it into
async workers.

---

## 9. Architecture Decision Records

Written to `docs/adr/` with an index at `docs/adr/README.md`, one per non-obvious decision.

| ADR | Decision | Trade-off |
|---|---|---|
| 0001 | `customer_tier` as point-in-time snapshot, not FK | Judge a ticket by the tier held when raised vs. normalised current truth |
| 0002 | `AbortPolicy` over `CallerRunsPolicy`; rejection ⇒ 503 + `Retry-After` — **`Superseded` by 0015, retained not deleted** | The reasoning trail is the valuable part; the `AbortPolicy` half of this decision still stands |
| 0003 | Dual AI provider profiles behind `ChatModel`/`EmbeddingModel` | Zero-cost local iteration vs. measurement fidelity; resume numbers from cloud |
| 0004 | DB-enforced invariants over application checks | Holds under concurrency and future callers; cost is exception-to-HTTP translation |
| 0005 | Defer API-key auth; `SERVICE` role instead | Intended design recorded; production concern, not a differentiator |
| 0006 | 404 over 403 for team isolation | 403 is an existence oracle; cost is less helpful diagnostics |
| 0007 | Testcontainers CI-first, never disabled, never H2 | Production fidelity vs. slower feedback loop |
| 0008 | Neon free tier for the `local` profile | Unblocks dev without a container runtime |
| 0009 | Deterministic pre-screen **before and independent of** the LLM | A guard keyed on the model's own prediction fails when the model is wrong |
| 0010 | Package by feature, not by layer | Change locality vs. tutorial familiarity |
| **0011** | **Persist-first ingestion; ticket row is the work item** | Survives restarts without an outbox table; cost is sweep + reaper machinery |
| **0012** | **`ingestion_state` separate from business `status`** | Keeps BR-A09's transition machine clean; cost is a second column |
| **0013** | **Pure domain core enforced by ArchUnit + injected `Clock`** | Verified boundary, deterministic time-dependent tests; cost is no inline `now()` |
| **0014** | **JaCoCo merged unit + integration coverage** | Honest 60 % gate vs. default config silently discarding IT coverage |
| **0015** | **Rejection ⇒ 202 + `ingestion.queue.rejected` — supersedes 0002** | A later design change (persist-first) invalidated 0002's premise: rejection is no longer data loss, so 503 would misreport durably-stored work. `AbortPolicy` itself is unchanged. |

---

## 10. Requirements traceability

| Requirement | Design section |
|---|---|
| FR-1 Domain model | §1, §4.1 |
| FR-2 Schema V1–V4 | §4 |
| FR-3 Auth | §3.1 |
| FR-4 Ingestion API | §2 |
| FR-5 Async | §2.1–2.6 (**FR-5's 503 superseded — see ADR-0002**) |
| FR-6 Abstention foundations | §3.3, §7 |
| FR-7 Business rules | §2.7 (A02), §3.2 (A10), §1 (A09) |
| FR-8 Error handling | §1 (`common/web`) |
| FR-9 CI | §6 |
| FR-10 Docs / ADRs | §9 |

### Requirements deltas — resolved

Both deltas below were **amended into the requirements document on 2026-09-05** (see its
CHANGELOG). Requirements and design are now in sync — there is **one source of truth**.

| Delta | Resolution |
|---|---|
| FR-5 / AC-6: queue saturation 503 → **202 + counter** | Amended in requirements; AC-6's test description rewritten to assert all four conditions |
| FR-2 V3: `ingestion_state`, `claimed_at`, `attempt_count`, `ingestion_error` | Amended in requirements §FR-2 V3 |
