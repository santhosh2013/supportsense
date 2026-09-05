# Requirements — SupportSense Milestone A1

**Slug:** `supportsense-a1`
**Source:** `C:\Users\ASANTH16\Downloads\TwoProject_Build_Spec.xlsx` (sheets 01–08, 13–16)
**Scope:** Milestone **A1 only** — Domain, schema, ingestion
**Date:** 2026-09-05
**Status:** **Approved** — all open questions resolved

---

## CHANGELOG

**AMENDED 2026-09-05 — FR-5 / AC-6: queue-rejection response 503 → 202 + counter.**
*Rationale:* the design introduced persist-first ingestion, in which the ticket row is
committed before the executor is touched. Rejection is therefore **no longer data loss** —
the sweep collects the row within 60 s. Returning 503 would **misreport the outcome to the
caller** and provoke retries for work already durably stored. `AbortPolicy` is retained:
the objective was always to stop the request thread executing the work, not to emit a
particular status code. Superseded ADR-0002 is marked `Superseded`, not deleted — the
reasoning trail is the valuable part.

**AMENDED 2026-09-05 — FR-2 V3:** added `ingestion_state`, `claimed_at`, `attempt_count`,
`ingestion_error` and `ix_ticket_ingestion_pending` to support the claim / sweep / reaper
mechanism. `PROCESSING` is deliberately **not** added to `Ticket.status`, which would
corrupt BR-A09's transition machine.

---

## 1. Context

SupportSense is an AI-powered support-ticket triage and resolution engine. It ingests
inbound tickets, classifies category / priority / owning team, detects duplicates,
retrieves similar resolved tickets and KB articles, drafts a grounded resolution with
citations, and **abstains** when confidence is low — routing to a human with a stated reason.

This document covers **milestone A1 only**: the domain model, database schema, JWT
authentication, asynchronous ticket ingestion, and the CI pipeline. No AI/LLM inference
is exercised in A1.

### Explicit non-goals for A1

| Out of scope | Deferred to |
|---|---|
| LLM classification, structured output, confidence capture | A2 |
| Confusion matrix / correction endpoints | A2 |
| pgvector, HNSW, embeddings, KB ingestion | A3 |
| Hybrid retrieval, RRF, re-ranking, SSE streaming | A4 |
| Approval-gated corpus feedback loop (BR-A06) | A5 |
| Eval harness, guardrails, semantic cache, Angular console | A6 |
| **P2 AssetOps — entirely** | Separate repository |

**SupportSense has zero dependency on AssetOps.** No shared code, no shared database,
no shared entities, its own seed data. This separation is deliberate and must not be
"helpfully" merged.

---

## 2. Stakeholders

| Role | Interest in A1 |
|---|---|
| **AGENT** (support agent) | Will consume tickets scoped to their own team (BR-A10) |
| **LEAD** (team lead) | Will approve corpus additions (A5); in A1 has elevated read scope |
| **ADMIN** | Bulk ingestion, seed management, eval runs |
| **Upstream ticket source** | Posts tickets via API; may retry — ingestion must be idempotent |
| **Hiring panel** (ultimate reader) | Every claim must be measurable with stored evidence |

---

## 3. Constraints

### 3.1 Locked technology (sheet 03 — no substitutions, no additions)

| Technology | Version | Notes |
|---|---|---|
| Java | 21 LTS | Records for DTOs, pattern matching, virtual threads. No preview features. |
| Spring Boot | 3.3.x | Pin the exact version in `pom.xml` |
| Spring Web | 3.3.x | RFC-7807 `ProblemDetail` only — never a bespoke error class |
| Spring Data JPA | 3.3.x | Every list endpoint uses `@EntityGraph` or `JOIN FETCH`. No N+1. |
| Spring Security | 6.x | Stateless JWT, BCrypt(10), `@PreAuthorize` at the **service** layer |
| Flyway | 10.x | Forward-only. `spring.jpa.hibernate.ddl-auto=validate`. Never `update`. |
| PostgreSQL | 16 | `pgvector/pgvector:pg16` image (plain `postgres:16` lacks the extension) |
| Redis | 7.x | Spring Cache, rate limiting (wired in A1, exercised later) |
| Testcontainers | 1.19+ | **No H2, ever** |
| springdoc-openapi | 2.x | Swagger UI at `/swagger-ui.html` |
| Micrometer | latest | Custom counters and timers are mandatory |
| Maven | — | Build tool (per sheet 15) |

### 3.2 Environment constraints (user-supplied)

| Constraint | Consequence for A1 |
|---|---|
| **No Docker on the dev machine** (`docker ps` → `CommandNotFoundException`) | Testcontainers tests are written normally — **never** `@Disabled`, **never** swapped for H2. They execute in **GitHub Actions CI on `ubuntu-latest`** where Docker is available. The CI workflow ships **in A1** so the tests run somewhere from day one. |
| Corporate Windows laptop behind a proxy; Docker Desktop licensing may block install | `docker-compose.yml` is still produced (`pgvector/pgvector:pg16`). Proxy-aware Docker setup documented in the README for later. |
| Local dev needs a working path without Docker | **Neon free tier** (Postgres 16, supports `CREATE EXTENSION vector`) backs the `local` profile. Connection string supplied via the **`SPRING_DATASOURCE_URL`** env var with **`sslmode=require`**. **Never committed.** Flyway must apply cleanly against it. |
| Local test runs must not require Docker | **Unit tests and `@WebMvcTest` slices must pass locally with zero Docker.** Only `@DataJpaTest` / `@SpringBootTest` (Testcontainers) require CI. |

### 3.3 AI provider strategy (wired in A1, exercised from A2)

Both profiles are wired now behind Spring AI's `ChatModel` / `EmbeddingModel` abstractions.

| Profile | Chat model | Embedding model | Dims |
|---|---|---|---|
| `local` | Ollama `llama3.1` | Ollama `nomic-embed-text` | 768 |
| `cloud` | Gemini `gemini-2.0-flash` | Gemini `text-embedding-004` | 768 |

- Identical 768 dimensionality → **the pgvector schema is provider-independent; switching providers requires no migration.**
- **Business code must never reference a provider directly** — profile + property switch only.
- Unit and slice tests mock the model. Only the eval suite (A6) calls a live model.
- **A1 requires no live model — model availability must never block A1.**
- Iterate locally on Ollama to save cost, but **measure `precision@5` and groundedness on the `cloud` profile** — Llama 3.1 8B is materially weaker at structured classification with calibrated confidence. Resume numbers come from the cloud profile.
- Ollama may be un-installable on a Ford-managed laptop and `llama3.1` 8B wants ~8 GB RAM; dual-profile wiring ensures this is never a blocker.

### 3.4 Non-negotiables (sheet 01)

1. Every business rule enforced **server-side** and covered by a test.
2. RFC-7807 `ProblemDetail` on **every** error, from a single `@ControllerAdvice`.
3. Flyway migrations, `ddl-auto=validate`.
4. Testcontainers, never H2.
5. No library outside sheet 03.
6. No secrets in source.

---

## 4. Repository layout

Git repo root is the workspace folder `Project1/`. **The Maven project does not sit at
the repo root** — A6 adds an Angular console, and a `package.json` beside a `pom.xml`
creates Maven-wrapper, `node_modules`, `.gitignore`, and CI-ambiguity problems.

```
Project1/                  <- git repo root
  api/                     <- Spring Boot (pom.xml here)
  web/                     <- Angular (added in A6)
  infra/                   <- docker-compose.yml, Terraform later
  eval/                    <- golden-set.json (A6)
  docs/                    <- ADRs, architecture diagram, metrics.md
  .github/workflows/       <- CI
  README.md
```

| Coordinate | Value |
|---|---|
| `groupId` | `io.github.santhosh2013` |
| `artifactId` | `supportsense-api` |
| Base package | `io.github.santhosh2013.supportsense` |

**Package by feature, not by layer.** `ticket/`, `kb/`, `triage/`, `retrieval/`, `auth/`,
`common/` — each owning its own controller / service / repository / dto.
Do **not** create top-level `controllers/`, `services/`, `repositories/` packages.

---

## 5. Functional requirements

### FR-1 — Domain model (persistence layer)

JPA entities internally; **Java records as DTOs at the boundary. Never expose an entity
from a controller.**

**A1 entities (fully implemented — schema + entity + repository + service + API):**
`User`, `Team`, `Category`, `Ticket`, `TicketEvent`

**A1 entities (persistence-only — schema + entity + bare `JpaRepository`, nothing else):**
`TriageResult`, `DuplicateLink`

> **Persistence-only means exactly that:** no service classes, no REST endpoints, no
> business logic touching `triage_results` or `duplicate_links` in A1. They exist so
> `ddl-auto=validate` is clean and A2 starts on solid ground.

**Deferred entirely (no schema in A1):** `KbArticle`, `KbChunk`,
`ResolvedTicketEmbedding`, `Suggestion`, `SuggestionFeedback`, `EvalCase`, `EvalRun`.

#### Enum sets

| Entity.field | Values |
|---|---|
| `User.role` | `AGENT`, `LEAD`, `ADMIN`, `SERVICE` |
| `Ticket.channel` | `EMAIL`, `WEB`, `CHAT`, `API` |
| `Ticket.status` | `NEW`, `TRIAGED`, `IN_PROGRESS`, `PENDING_CUSTOMER`, `RESOLVED`, `CLOSED`, `DUPLICATE` |
| `Ticket.priority` | `P1`, `P2`, `P3`, `P4` |
| `Ticket.resolvedBy` | `HUMAN`, `AI_ACCEPTED`, `AI_AUTO` |
| `Ticket.customerTier` | `FREE`, `PRO`, `ENTERPRISE` |
| `TicketEvent.eventType` | `CREATED`, `TRIAGED`, `ASSIGNED`, `STATUS_CHANGED`, `SUGGESTION_GENERATED`, `SUGGESTION_ACCEPTED`, `RESOLVED`, `MARKED_DUPLICATE` |
| `TriageResult.abstentionReason` | `NONE`, `LOW_CONFIDENCE`, `LOW_SIMILARITY`, `ENTERPRISE_TIER`, `SENSITIVE_CATEGORY`, `KEYWORD_PRESCREEN`, `TOOL_FAILURE` |
| `DuplicateLink.status` | `SUGGESTED`, `CONFIRMED`, `REJECTED` |

---

### FR-2 — Database schema (Flyway V1–V4)

Forward-only. `ddl-auto=validate`.

#### V1__init.sql — `users`, `teams`, `categories`

- Indexes: `ux_users_email_lower ON lower(email)`, `ux_team_slug`, `ux_category_slug`, `ix_category_parent`
- `categories.parent_id` — hierarchical, max depth 2
- **`categories.auto_answer_blocked boolean NOT NULL DEFAULT false`** — topic-level auto-answer guard (see FR-6)

#### V2__seed_taxonomy.sql — data only

**5 teams:** `Billing Ops`, `Platform Support`, `Identity & Access`, `Data & Integrations`, `Customer Success`

**10 leaf categories:**

| slug | Owning team | `auto_answer_blocked` |
|---|---|---|
| `billing-invoice` | Billing Ops | **true** |
| `billing-refund` | Billing Ops | **true** |
| `legal-compliance` | Customer Success | **true** |
| `security-privacy` | Identity & Access | **true** |
| `account-login-access` | Identity & Access | false |
| `account-provisioning` | Identity & Access | false |
| `api-integration-error` | Data & Integrations | false |
| `data-import-export` | Data & Integrations | false |
| `performance-latency` | Platform Support | false |
| `bug-report` | Platform Support | false |

- **1 ADMIN user**
- **Idempotent** — upsert on natural key (`slug`, `lower(email)`). Re-running must never duplicate rows.
- Admin bootstrap password read from an **environment variable**. **Startup fails fast** if the variable is absent in any profile other than `local` — no default fallback, ever.

#### V3__tickets.sql — `tickets`, `ticket_events`

Indexes and constraints from sheet 05:

| Object | Definition |
|---|---|
| `ux_ticket_external_ref` | UNIQUE on `external_ref` — drives BR-A02 idempotency |
| `ix_ticket_status_sla` | `(status, sla_due_at) WHERE resolved_at IS NULL` — cheap SLA breach scan |
| `ix_ticket_category_created` | `(category_id, created_at)` |
| `ix_event_ticket_occurred` | `(ticket_id, occurred_at DESC)` |
| `version` | `integer NOT NULL DEFAULT 0` — optimistic locking |
| CHECK | `resolved_at IS NULL OR resolved_at >= created_at` |

**Additional A1 columns on `tickets` (beyond sheet 04) — required to make the headline metric measurable:**

| Column | Type | Rationale |
|---|---|---|
| `customer_tier` | enum `{FREE, PRO, ENTERPRISE}` NOT NULL DEFAULT `FREE` | Tier-based abstention (FR-6). **Point-in-time snapshot, not an FK** — a ticket is judged by the tier held when raised, even if the customer later downgrades. Recorded as an ADR. |
| `auto_answered` | `boolean NOT NULL DEFAULT false` | Without it, false-deflection is **not computable at all** — auto-answered tickets cannot be separated from human-resolved ones. |
| `auto_answered_at` | `timestamptz NULL` | When the auto-answer occurred |
| `first_resolved_at` | `timestamptz NULL` | The 48h reopen window is measured from **first** resolution, not `created_at` and not the most recent resolution |
| `ingestion_state` | enum `{PENDING, PROCESSING, DONE, FAILED}` NOT NULL DEFAULT `PENDING` | Pipeline state, kept separate from business `status` (FR-5) |
| `claimed_at` | `timestamptz NULL` | Claim timestamp; drives the 15-minute reaper threshold |
| `attempt_count` | `integer NOT NULL DEFAULT 0` | Attempt cap of 3 stops poison tickets retrying forever |
| `ingestion_error` | `text NULL` | Persisted failure reason — a silent async failure is the classic bug here |

Supporting index: `ix_ticket_ingestion_pending ON (ingestion_state, claimed_at) WHERE ingestion_state IN ('PENDING','PROCESSING')` — keeps sweep and reaper cheap.

**Reopen index — predicate corrected to exclude forward transitions:**

```sql
CREATE INDEX ix_event_reopen ON ticket_events(ticket_id, occurred_at)
WHERE from_status IN ('RESOLVED','CLOSED')
  AND to_status NOT IN ('RESOLVED','CLOSED');
```

> `RESOLVED → CLOSED` is a normal forward closure, **not** a reopen. Counting it would
> silently inflate false-deflection rate — the one number that most needs to be honest.

#### V4__triage.sql — `triage_results`, `duplicate_links`

| Object | Definition |
|---|---|
| `ix_triage_ticket` | `(ticket_id)` |
| `ix_triage_abstained` | `(abstained, created_at)` |
| `category_confidence`, `priority_confidence` | **`numeric(4,3)`** — **not** `float`/`real` |
| CHECK | `category_confidence >= 0 AND category_confidence <= 1` (same for priority) |
| `abstention_reason` | enum, see FR-1 |
| `duplicate_links` pair | **Canonically ordered: `ticket_a_id < ticket_b_id`**, enforced by CHECK, with UNIQUE index on `(ticket_a_id, ticket_b_id)` |
| CHECK | `similarity BETWEEN 0 AND 1` |

> **Why `numeric(4,3)` and not float:** confidence thresholds drive the abstention rule
> in A2. Floating-point comparison at a threshold boundary is a real and well-known bug source.

> **Why canonical pair ordering:** without it, A→B and B→A both insert and every
> duplicate appears twice. The CHECK plus UNIQUE index makes the invariant structural.

---

### FR-3 — Authentication & authorisation

| Endpoint | Method | Roles | Request | Response | Codes |
|---|---|---|---|---|---|
| `/api/auth/register` | POST | public | `RegisterRequest{email,password,fullName,teamId}` | `AuthResponse` | 201 / 400 / 409 |
| `/api/auth/login` | POST | public | `LoginRequest` | `AuthResponse{accessToken,refreshToken,user}` | 200 / 401 |
| `/api/auth/me` | GET | any | — | `UserResponse` | 200 / 401 |

**Token policy:**

- **HS256.** Access token **15 min**, refresh token **7 days**.
- Signing key from an **environment variable** — never in source.
- **BCrypt strength 10.** Stateless — no sessions. `@PreAuthorize` at the **service** layer.
- Duplicate email on register → **409**.
- Login failure → **generic message**; never reveal whether the email exists.

**Refresh-token hardening (a 7-day plaintext refresh token is the weakest link in the default set):**

1. Store only a **SHA-256 hash** of the refresh token — never the token itself.
2. **Rotate on every use.**
3. **Detect reuse of an already-rotated token as a theft signal → revoke the entire token family.**

**API-key authentication is deferred.** A1 is **JWT-only**. Machine ingestion authenticates
as a user holding a **`SERVICE`** role. The intended API-key design (SHA-256 hashed key,
non-secret prefix for lookup, scoped to a team, separately rate-limited) is captured in
ADR-0005 so the reasoning survives. Revisit in A6 only if time allows — it is a production
concern, not a differentiator.

> `User.role` therefore becomes `{AGENT, LEAD, ADMIN, SERVICE}` in A1.

---

### FR-4 — Ticket ingestion

| Endpoint | Method | Roles | Request | Response | Codes |
|---|---|---|---|---|---|
| `/api/tickets` | POST | any (JWT) | `CreateTicketRequest{externalRef,subject,body,channel,customerEmail,customerTier}` | `TicketResponse{id,status:NEW}` | **202** / 200 / 400 / **503** |
| `/api/tickets/bulk` | POST | ADMIN | `List<CreateTicketRequest>` (max 500) | `BulkIngestResponse{accepted,duplicates,rejected}` | 202 / 400 / 413 |
| `/api/tickets` | GET | any | `?status&priority&categoryId&teamId&assigneeId&q&page&size&sort` | `Page<TicketResponse>` | 200 / 400 / 401 / 403 / 404 |
| `/api/tickets/{id}` | GET | any | — | `TicketDetailResponse` | 200 / 400 / 401 / 403 / 404 |
| `/actuator/health` | GET | public | — | `{status}` | 200 / 503 |

- Bulk ingestion allows **partial success** — report per-item outcomes.
- List endpoints: **server-side pagination, max size 100**, `@EntityGraph` on category / team / assignee. **No N+1.**
- Triage-related filters (`abstained`, `slaBreached`) are deferred to A2/A6.

---

### FR-5 — Asynchronous ingestion (BR-A01)

`POST /api/tickets` returns **202 immediately**; downstream processing is asynchronous.

**Executor:** bounded `ThreadPoolTaskExecutor` — core 4 / max 8 / queue 500.
Virtual threads enabled for the servlet layer (sheet 03). The bounded pool is what makes
backpressure provable.

**Persist first, dispatch after commit.** The ticket row **is** the work item — no separate
job table. The row is committed (`status = NEW`, `ingestion_state = PENDING`) **before** the
executor is touched, and dispatch fires from `@TransactionalEventListener(AFTER_COMMIT)`.
Dispatching inside the transaction would let the worker start before the row is visible.

**Two state columns, deliberately separate.** `PROCESSING` is a *pipeline* state, not a
*business lifecycle* state — placing it in `Ticket.status` would corrupt BR-A09's transition
machine.

| Column | Values | Governed by |
|---|---|---|
| `status` | `NEW, TRIAGED, IN_PROGRESS, PENDING_CUSTOMER, RESOLVED, CLOSED, DUPLICATE` | BR-A09; human/business actions |
| `ingestion_state` | `PENDING, PROCESSING, DONE, FAILED` | The async pipeline only |

**Rejection policy — `AbortPolicy`, not `CallerRunsPolicy`.**

> `CallerRunsPolicy` makes the **request thread** execute the ingestion once the queue
> fills, which directly violates BR-A01's sub-400ms contract — under load the endpoint
> would silently begin blocking for seconds. `AbortPolicy` refuses to let the request thread
> execute the work; that, not the status code, was always the point.

**On `RejectedExecutionException` → return 202** *(amended — see CHANGELOG)*, increment the
`ingestion.queue.rejected` counter, and leave `ingestion_state = PENDING`. The sweep collects
it within 60 s.

> The ticket is already durably persisted, so rejection is **not** data loss. Returning 503
> would misreport the outcome to the caller and provoke retries the system does not need.

**Sweep and reaper** (both idempotent, safe across concurrent Cloud Run instances):

| Job | Interval | Action |
|---|---|---|
| Sweep | 60 s + at startup | Claim and dispatch `ingestion_state = PENDING` rows |
| Reaper | 60 s | `PROCESSING` with `claimed_at` older than 15 min → back to `PENDING`; `attempt_count >= 3` → `FAILED` |

**Claim is a conditional update — no distributed lock:**

```sql
UPDATE tickets SET ingestion_state='PROCESSING', claimed_at=:now, attempt_count=attempt_count+1
 WHERE id=:id AND ingestion_state='PENDING';
```

Proceed only if **exactly one row was affected**.

**Context propagation:** decorate the executor with a `TaskDecorator` that copies the
**MDC trace/correlation ID** and the **`SecurityContext`** into the worker thread. Without
it, ingestion logs are uncorrelated from the request that triggered them and the audit
trail is lost.

**Failure handling:** register an `AsyncUncaughtExceptionHandler` that sets the ingestion
row to `FAILED` with the error message **persisted**. A silent async failure leaving
status stuck on `PROCESSING` forever is the classic bug in this design.

---

### FR-6 — Abstention & escalation foundations (schema only in A1)

Abstention is a **first-class feature, not an edge case**. The system must decline to
auto-answer on: low classification confidence, low retrieval similarity, **enterprise-tier
customers**, and **billing / refund / legal topics**. The logic is built in **A2**; A1
provides the data it reads.

**Defence in depth on sensitive topics — two independent gates:**

| Gate | Mechanism | Runs |
|---|---|---|
| 1. Deterministic pre-screen | Keyword/regex over **raw** ticket `subject + body`. Terms live in **config**, not hardcoded. | **Before and independently of the LLM** |
| 2. Category flag | `Category.auto_answer_blocked` keyed off the **predicted** category | After classification |

**If either gate trips, auto-answer is blocked.**

#### Pre-screen term list

Config-driven under `supportsense.prescreen.terms` in `application.yml` — **never hardcoded**.
Matching is **case-insensitive with word boundaries (`\b`)**, not substring.

> Substring matching produces false positives — `sue` matches inside `issue`.

| Group | Terms |
|---|---|
| Billing | refund, chargeback, invoice, overcharge, billing dispute, cancel subscription, downgrade, prorate |
| Legal | lawsuit, legal, attorney, solicitor, subpoena, liability, terms of service violation |
| Compliance / privacy | GDPR, CCPA, DPA, data deletion, right to be forgotten, breach, PII |
| Escalation signals | escalate to manager, unacceptable, cancel my account, speak to a supervisor |

A match sets `abstention_reason = KEYWORD_PRESCREEN` and blocks auto-answer.

> **Why both are required:** a gate keyed only on the LLM's own category prediction fails
> exactly when the model is wrong — a billing ticket misclassified as "technical" would
> sail straight past it. The guard must not depend on the thing it is guarding against.

`triage_results.abstention_reason` records **which gate fired**. Without it you can see
*that* the system abstained but never *why* — and the abstention-reason distribution is
the single most useful analytics view in A6.

---

### FR-7 — Business rules enforced in A1

| ID | Rule | Enforcement | Required test |
|---|---|---|---|
| **BR-A01** | `POST /api/tickets` returns 202 immediately; processing is async | `@Async` + bounded executor, `AbortPolicy` | Ingest a 5000-word ticket → response **< 400 ms**. Fill the queue → **503**, not a slow 202. |
| **BR-A02** | Re-posting the same `externalRef` must not create a second ticket | `ux_ticket_external_ref`; catch the violation, return **200** with the existing ticket | **Idempotency test:** post the same payload **3×** → exactly **1 row** |
| **BR-A09** | Status transitions follow `NEW→TRIAGED→IN_PROGRESS→(PENDING_CUSTOMER)→RESOLVED→CLOSED`; `DUPLICATE` reachable from `NEW`/`TRIAGED` only | Enum transition map; illegal → **409 listing allowed targets** | Parameterised test over **every** legal and illegal pair |
| **BR-A10** | A user may see only tickets belonging to their team, unless `LEAD` or `ADMIN` | **Repository-level filtering by principal** — not a controller check | Agent from team A requests a team B ticket → **404** (not 403 — avoid leaking existence) |
| **BR-A12** | All prompts live in `resources/prompts/*.st`, version-tagged; never inline Java strings | Spring AI `PromptTemplate` from classpath | Test asserts prompts load and render with expected placeholders (structure established in A1) |

Rules **BR-A03 – BR-A08** and **BR-A11** are deferred to A3–A6.

---

### FR-8 — Error handling

- **RFC-7807 `ProblemDetail` on every error**, from a **single `@ControllerAdvice`**.
- Never a bespoke error class.
- 409 responses for illegal transitions must **list the allowed target statuses**.
- 503 from queue saturation must carry `Retry-After`.

---

### FR-9 — CI pipeline (ships in A1)

GitHub Actions on `ubuntu-latest`, where Docker is available so Testcontainers execute for real.

| Step | Requirement |
|---|---|
| Checkout | **`fetch-depth: 0`** — gitleaks must scan **full history**, not just the diff |
| Java setup | `actions/setup-java` with **`cache: maven`** — otherwise every run re-downloads Spring Boot and CI takes 4+ minutes from day one |
| Build + test | `mvn verify` — all Testcontainers integration tests execute |
| **Flyway drift gate** | Start the app against a Testcontainer Postgres with `ddl-auto=validate`; assert clean startup. **Catches entity/migration mismatch — the most common A1→A2 breakage.** |
| Coverage | **JaCoCo, 60% floor** (sheet 13), with **exclusions for generated code, config classes, and DTO records** — otherwise the gate measures the wrong thing |
| **gitleaks** | Secret scanning over full history. Added **now**: adding it later means scanning a repo that may already have a secret in its history — at which point you are rewriting history, not adding a step. Cost now: ~4 lines. |
| Test report | Publish on failure (`dorny/test-reporter` or upload surefire XML as an artifact) so a red build is **diagnosable without re-running locally** — which matters more than usual, since Testcontainers cannot run on the dev machine |

---

### FR-10 — Documentation deliverables (A1)

| File | Content |
|---|---|
| `docs/metrics.md` | **Written now, before A6**, so the implementation cannot drift from the claim: <br>`false-deflection rate = (tickets WHERE auto_answered = true AND a reopen event occurred within 48h of first_resolved_at) ÷ (tickets WHERE auto_answered = true)` <br>Headline metric is **deflection rate paired with false-deflection rate — never deflection alone.** |
| `docs/adr/README.md` | **ADR index** — table of every decision, status, and date |
| `README.md` | Setup (Docker and no-Docker paths), proxy-aware Docker notes, Neon connection instructions |
| `infra/docker-compose.yml` | `pgvector/pgvector:pg16` + Redis 7 |

#### ADR index (A1)

Phase 2 opens with `docs/adr/README.md` and writes one ADR per non-obvious decision as it
is made. This folder is the highest-value artefact in a code walkthrough — it is the
difference between *"I built this"* and *"I decided this, and here is what I traded away."*

| ADR | Decision | Trade-off captured |
|---|---|---|
| `0001-customer-tier-snapshot.md` | `customer_tier` is a point-in-time denormalised snapshot on `tickets`, not an FK to a customer entity | Judging a ticket by the tier held **when raised** vs. normalised current-truth; why the denormalisation is deliberate, not an accident |
| `0002-async-rejection-policy.md` | `AbortPolicy` + 503 + `Retry-After`, **not** `CallerRunsPolicy` | Visible, testable backpressure vs. saturation silently disguised as request latency — and why the latter breaks BR-A01's contract |
| `0003-dual-ai-provider-profiles.md` | Both Ollama and Gemini wired behind `ChatModel`/`EmbeddingModel`; identical 768 dims | Zero-cost local iteration vs. measurement fidelity; why resume numbers come from the cloud profile |
| `0004-db-invariants-over-app-checks.md` | Core invariants enforced by the **database**, not application code: `ux_ticket_external_ref`, canonical `ticket_a_id < ticket_b_id` CHECK + UNIQUE, `numeric(4,3)` + range CHECKs | A constraint holds under concurrency, retries, and future callers that bypass the service layer; an app-level check does not. Cost: violations surface as exceptions needing translation to 200/409 |
| `0005-defer-api-key-auth.md` | A1 is JWT-only; machine ingestion uses a `SERVICE`-role user | Intended design recorded (SHA-256 hashed key, non-secret lookup prefix, team-scoped, separately rate-limited). Deferred because it is a production concern, not an interview differentiator |
| `0006-404-over-403-for-team-isolation.md` | Cross-team access returns **404**, not 403 (BR-A10) | 403 confirms the resource exists — an existence oracle. 404 leaks nothing, at the cost of slightly less helpful diagnostics for legitimate users |
| `0007-testcontainers-ci-first.md` | Testcontainers tests never disabled and never swapped for H2, despite no local Docker; CI is the execution venue | Behavioural fidelity with production Postgres vs. a slower feedback loop; mitigated by rich local `@WebMvcTest`/unit coverage and CI-published surefire reports |
| `0008-neon-for-local-profile.md` | Neon free tier (Postgres 16, `CREATE EXTENSION vector`) via `SPRING_DATASOURCE_URL` with `sslmode=require` | Unblocks local dev without a container runtime; connection string never committed. `docker-compose.yml` still ships for when a runtime is available |
| `0009-prescreen-before-llm.md` | Deterministic word-boundary keyword pre-screen runs **before and independently of** the LLM, alongside the `auto_answer_blocked` category flag | A guard keyed only on the model's own prediction fails exactly when the model is wrong. Cost: false positives on benign mentions — mitigated by `\b` matching over substring |
| `0010-package-by-feature.md` | `ticket/`, `kb/`, `triage/`, `retrieval/`, `auth/`, `common/` — no top-level `controllers/`/`services/`/`repositories/` | Change locality and clear module seams vs. the layered layout most Spring tutorials use |

---

## 6. Acceptance criteria (binary — from sheet 14, milestone A1)

| # | Criterion | Verification |
|---|---|---|
| AC-1 | Flyway V1–V4 apply cleanly against PostgreSQL 16 | CI Testcontainers run |
| AC-2 | `ddl-auto=validate` starts with **no drift** | CI Flyway drift gate |
| AC-3 | **Ingest 200 seed tickets** successfully | Bulk endpoint integration test |
| AC-4 | Posting the same `externalRef` **3×** creates **exactly 1 row** | BR-A02 idempotency test |
| AC-5 | Ingestion response **< 400 ms** for a 5000-word ticket | BR-A01 timing test |
| AC-6 | Queue saturation returns **202** — and the ticket is **still processed**. The test must assert **all four**: (a) response is 202, **not** a slow 202 and **not** 503; (b) the ticket is persisted with `status='NEW'`, `ingestion_state='PENDING'`; (c) `ingestion.queue.rejected` is incremented; (d) the sweep subsequently processes it. | BR-A01 backpressure test *(amended — see CHANGELOG)* |
| AC-7 | Every legal/illegal status transition behaves per BR-A09 | Parameterised transition test |
| AC-8 | Cross-team ticket access returns **404** | BR-A10 security test |
| AC-9 | Every error response is an RFC-7807 `ProblemDetail` | `@WebMvcTest` slice assertions |
| AC-10 | Swagger UI live at `/swagger-ui.html` | Manual + smoke test |
| AC-11 | CI is **green**: build, tests, Flyway drift, JaCoCo ≥ 60%, gitleaks clean | GitHub Actions run |
| AC-12 | **Unit tests and `@WebMvcTest` slices pass locally with no Docker** | Local `mvn test -Dgroups=...` |
| AC-13 | Seed migration is idempotent — re-running duplicates nothing | Repeated-migration test |
| AC-14 | Startup **fails fast** when the admin bootstrap password env var is absent outside `local` | Context-load negative test |

---

## 7. BDD scenarios

```gherkin
Feature: Idempotent ticket ingestion (BR-A02)

  Scenario: The same external reference is posted three times
    Given an upstream system with externalRef "EXT-1001"
    When it POSTs the identical ticket payload 3 times
    Then the first response is 202 Accepted
    And the second and third responses are 200 OK carrying the existing ticket id
    And exactly 1 row exists in the tickets table for "EXT-1001"

Feature: Asynchronous ingestion under load (BR-A01)

  Scenario: A large ticket is accepted quickly
    Given a ticket body of 5000 words
    When it is POSTed to /api/tickets
    Then the response status is 202
    And the response is returned in under 400 milliseconds

  Scenario: The ingestion queue is saturated
    Given the bounded executor queue is full
    When another ticket is POSTed to /api/tickets
    Then the response status is 202
    And the response is returned in under 400 milliseconds
    And the request thread did not execute the ingestion
    And the ticket is persisted with status "NEW" and ingestion_state "PENDING"
    And the ingestion.queue.rejected counter is incremented
    When the ingestion sweep runs
    Then the ticket is claimed and processed

  Scenario: A worker dies mid-processing
    Given a ticket has ingestion_state "PROCESSING" claimed more than 15 minutes ago
    When the reaper runs
    Then the ticket returns to ingestion_state "PENDING"
    And its attempt_count has been incremented

  Scenario: A poison ticket exhausts its attempts
    Given a ticket has failed processing 3 times
    When the reaper runs
    Then the ticket ingestion_state becomes "FAILED"
    And the error message is persisted

  Scenario: Two instances start simultaneously
    Given two application instances sweep the same PENDING ticket concurrently
    When both attempt the conditional claim
    Then exactly one update affects a row
    And the ticket is processed exactly once

Feature: Ticket lifecycle (BR-A09)

  Scenario Outline: Legal transitions are accepted
    Given a ticket in status <from>
    When its status is changed to <to>
    Then the response status is 200
    Examples:
      | from             | to               |
      | NEW              | TRIAGED          |
      | TRIAGED          | IN_PROGRESS      |
      | IN_PROGRESS      | PENDING_CUSTOMER |
      | IN_PROGRESS      | RESOLVED         |
      | RESOLVED         | CLOSED           |
      | NEW              | DUPLICATE        |
      | TRIAGED          | DUPLICATE        |

  Scenario Outline: Illegal transitions are rejected with guidance
    Given a ticket in status <from>
    When its status is changed to <to>
    Then the response status is 409
    And the ProblemDetail lists the allowed target statuses
    Examples:
      | from      | to        |
      | NEW       | RESOLVED  |
      | CLOSED    | NEW       |
      | RESOLVED  | DUPLICATE |

Feature: Team isolation (BR-A10)

  Scenario: An agent requests another team's ticket
    Given agent "alice" belongs to team "billing"
    And a ticket exists owned by team "platform"
    When alice GETs that ticket by id
    Then the response status is 404
    And the response does not reveal that the ticket exists

  Scenario: A lead reads across teams
    Given lead "bob" has role LEAD
    When bob GETs a ticket owned by any team
    Then the response status is 200

Feature: Refresh-token theft detection

  Scenario: A rotated refresh token is replayed
    Given a refresh token R1 has been exchanged and rotated to R2
    When R1 is presented again
    Then the response status is 401
    And the entire token family is revoked
    And R2 no longer grants access

Feature: Seed idempotency

  Scenario: Migrations are applied against an already-seeded database
    Given the taxonomy seed has already run
    When the seed migration logic is applied again
    Then exactly 5 teams and 10 leaf categories exist
    And exactly 1 ADMIN user exists

Feature: Fail-fast on missing bootstrap credential

  Scenario: The admin password env var is absent outside local
    Given the active profile is "cloud"
    And the admin bootstrap password environment variable is not set
    When the application starts
    Then startup fails with a clear configuration error
    And no default password is used
```

---

## 8. Dependencies

| Dependency | Status | Impact if unavailable |
|---|---|---|
| GitHub Actions (Docker on `ubuntu-latest`) | Assumed available | **Blocking** — the only place Testcontainers can run |
| Neon free tier (Postgres 16 + pgvector) for `local` profile | Confirmed | Non-blocking — CI still validates |
| Ollama / Gemini | Not required for A1 | None — A1 exercises no model |
| Redis 7 | Wired, not exercised in A1 | None |
| Docker Desktop locally | **Unavailable** | Mitigated by CI-first testing strategy |

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R-1 | No local Docker → integration failures discovered only in CI, slowing the loop | Rich `@WebMvcTest` + unit coverage locally; CI publishes surefire reports on failure so red builds are diagnosable without re-running |
| R-2 | `ddl-auto=validate` drift between entities and migrations | Dedicated CI Flyway drift gate in A1 |
| R-3 | Async failures leaving status stuck on `PROCESSING` | `AsyncUncaughtExceptionHandler` persists `FAILED` + error message |
| R-4 | Adding columns beyond sheet 04 causes divergence from the workbook | Every addition documented here with rationale; ADRs written for the non-obvious ones |
| R-5 | Corporate proxy blocking Maven dependency resolution | Document proxy settings in README; CI is unaffected |
| R-6 | Sheet 05 places `CREATE EXTENSION vector` in V5, so pgvector is not load-bearing until A3 | Use the `pgvector/pgvector:pg16` image from A1 anyway — forward-compatible, zero cost |

---

## 10. Open questions

**All open questions are resolved.** No blockers remain.

| # | Question | Resolution | ADR |
|---|---|---|---|
| OQ-1 | Keyword list for the sensitive-topic pre-screen | Config-driven under `supportsense.prescreen.terms`; case-insensitive **word-boundary** matching; 4 seed groups (§FR-6) | `0009` |
| OQ-2 | Seed teams and categories | 5 teams, 10 leaf categories with owning team and `auto_answer_blocked` flags; idempotent upsert on slug (§FR-2 V2) | — |
| OQ-3 | External Postgres for local dev | **Neon** free tier via `SPRING_DATASOURCE_URL` + `sslmode=require`, never committed; `docker-compose.yml` still ships | `0008` |
| OQ-4 | API-key authentication | **Deferred.** JWT-only in A1; machine ingestion uses a `SERVICE`-role user. Intended design recorded. | `0005` |

---

## 11. Traceability

| Requirement | Source | Acceptance |
|---|---|---|
| FR-1 Domain model | Sheet 04 | AC-1, AC-2 |
| FR-2 Schema V1–V4 | Sheet 05 | AC-1, AC-2, AC-13 |
| FR-3 Auth | Sheet 06 (AUTH), sheet 13 (Security) | AC-9, AC-14 |
| FR-4 Ingestion API | Sheet 06 (TICKET INGESTION) | AC-3, AC-4 |
| FR-5 Async | Sheet 07 BR-A01 | AC-5, AC-6 |
| FR-6 Abstention foundations | Sheet 07 BR-A07, sheet 08 stage 1, user elicitation | AC-1 |
| FR-7 Business rules | Sheet 07 | AC-4, AC-7, AC-8 |
| FR-8 Error handling | Sheet 01 non-negotiables, sheet 06 | AC-9 |
| FR-9 CI | Sheet 13, sheet 14 A1, user elicitation | AC-11, AC-12 |
| FR-10 Docs | Sheet 16, user elicitation | AC-11 |
