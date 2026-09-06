# Code Review Summary — supportsense-a1

**Phase:** 4 (Code Review)
**Mode:** Dual-model blind review (`blueprint-code-review` + `blueprint-code-review-altmodel`, run in parallel, no cross-visibility)
**Final verdict:** ✅ **APPROVE**
**Rounds completed:** 3 (initial review → Blocker remediation → Should-fix remediation)
**CI status:** ✅ Green on GitHub Actions (full Surefire + Failsafe suite, JaCoCo merged coverage gate ≥ 60%)

---

## Findings Summary

| # | Severity | Finding | Found by | Status |
|---|----------|---------|----------|--------|
| BLK-1 | Blocker | `IngestionSweepService.reap()` ran the whole stale-ticket batch in one transaction — one failure rolled back unrelated tickets' applied fixes | alt-model | ✅ Fixed, CI-verified |
| BLK-2 | Blocker | `untriagedOrphanThreshold` config was read by nothing — a `NEW` ticket with `team_id NULL` that was never claimed could stay invisible indefinitely under BR-A10 | primary | ✅ Fixed, CI-verified |
| BLK-3 | Blocker | `POST /api/tickets/bulk` did not exist despite being required by requirements and the execution plan | primary | ✅ Fixed, CI-verified |
| BONUS | Blocker-adjacent | 4th self-invocation instance (`OrphanTicketService.routeNeverClaimedOrphan` → `this::routeToFallbackTeam`) introduced while fixing BLK-2 | self re-sweep | ✅ Fixed |
| SHF-1 | Should-fix | `@PreAuthorize` annotations inert — no `@EnableMethodSecurity` registered anywhere | primary | ✅ Fixed, CI-verified |
| SHF-2 | Should-fix | JaCoCo `**/config/**` exclusion swept up real logic (`RequiredSecretsValidator`, `IngestionUncaughtExceptionHandler`) | both | ✅ Fixed, CI-verified |
| SHF-3 | Should-fix | JaCoCo `**/dto/**` exclusion was dead config — no `dto` package exists | primary | ✅ Fixed, CI-verified |
| SHF-4 | Should-fix | JWT algorithm pinning asserted by reasoning, never proven by a test | both (severity disputed) | ✅ Fixed, CI-verified |
| SHF-5 | Should-fix | No negative test proving the `AFTER_COMMIT` listener does **not** fire on a rolled-back creation transaction | alt-model | ✅ Fixed, CI-verified |
| SHF-6 | Should-fix | Literal test-only secrets in `application-test.yml` / `application-local.yml` | both (both rated benign) | ⏸️ Deferred to A2 by user, tracked in README |
| REM-1 | Correctness (found during remediation) | Bulk validation ran in Spring MVC's `List<@Valid …>` before the controller, making an in-batch `REJECTED` outcome unreachable | self | ✅ Fixed, CI-verified |
| REM-2 | Test isolation (found in CI) | `AuthFlowIT.seedProducesExpectedTaxonomy` asserted a bare `count(*) WHERE role='ADMIN' = 1`; method-security tests legitimately promote fixture users to `ADMIN` | CI | ✅ Fixed, CI-verified |

**Totals:** 12 findings · 11 fixed · 1 deferred (with user approval and tracked follow-up) · 0 unresolved

---

## Blocker Detail & Evidence

### BLK-1 — Reaper batch transaction scope
Extracted `ReaperItemProcessor` (`@Transactional(REQUIRES_NEW)`, invoked through its own Spring proxy, never self-invoked), called per-item inside a try/catch in `reap()`.

**Evidence:**
- `IngestionSweepServiceTest` — unit, mocked, per-item isolation
- `IngestionReaperIT.oneFailingTicketDoesNotRollBackUnrelatedTicketsInTheSameBatch` — Testcontainers; deliberately breaks the fallback team to force a *real* failure and asserts the other two tickets still reached `PENDING`

### BLK-2 — Never-claimed orphan path
New disjoint query and scheduler: `status=NEW AND ingestion_state=PENDING AND claimed_at IS NULL AND team IS NULL AND created_at < cutoff`. Provably cannot overlap the existing reaper predicate (which requires `PROCESSING` + `claimed_at NOT NULL`). Same 3-attempt cap and `customer-success` fallback routing. Uses the injected `TimeSource`.

**Evidence:** `IngestionSweepServiceTest` — clock/threshold correctness, per-item isolation.

### BLK-3 — Missing bulk endpoint
`POST /api/tickets/bulk` implemented: max 500 enforced with **400, not silent truncation**; each item independently isolated through the existing `TicketIngestionService.ingest()` → `TicketInsertAttempt` `REQUIRES_NEW` path; honest per-item `accepted`/`duplicate`/`rejected` outcome array.

**Evidence:** `TicketBulkIngestionIT` — Testcontainers; real duplicate + real accept + real rejection in one batch; 501-item batch rejected with zero rows written.

---

## Should-fix Detail & Evidence

1. **`@EnableMethodSecurity`** added to the component-scanned, unprofiled `SecurityConfig`, activating three previously-inert annotations: `TicketService.listVisibleTickets`, `TicketService.getVisibleTicket`, `TicketController.createBulk`.
   **Evidence:** `TicketMethodSecurityIT` (full context, not a WebMvc slice) — JWT-authenticated `AGENT` gets 403 on bulk; `ADMIN` gets 202 after re-login to obtain a JWT carrying the `ADMIN` claim.
2. **JaCoCo `**/config/**`** narrowed to bean-wiring classes plus the properties record only. `RequiredSecretsValidator`, `IngestionUncaughtExceptionHandler`, and `MissingRequiredSecretException` are now measured.
3. **Dead `**/dto/**` exclusion removed.** No replacement broad exclusion introduced.
4. **JWT generation explicitly pins `Jwts.SIG.HS256`.**
   **Evidence:** `JwtAlgorithmSecurityTest` — `alg:none`, wrong-key HMAC, tampered signature, expired token, all rejected by the real parser.
   *Honest note:* forged-token rejection already held before this change, because typed `verifyWith(SecretKey)` rejects unsigned/wrong-key tokens. This closes the **evidence gap** review identified; it does not patch a pre-existing vulnerability, and no claim to the contrary is made.
5. **`TicketDispatchRollbackIT`** — mocks `IngestionDispatchPort`, inserts once successfully to prove the listener *does* fire, resets the mock, then forces a duplicate-key rollback and asserts zero dispatch interactions.
6. **Deferred (user-approved):** literal local/test fixture credentials remain as clearly-fake, deterministic non-production values. README tracks the A2 follow-up and its rationale.

---

## Hard Stops Honored

None of the following were used at any point during remediation:

- ❌ `@Disabled` / `@Ignore`
- ❌ Lowering the JaCoCo threshold or adding exclusions to pass coverage
- ❌ Substituting H2 or an embedded DB for Testcontainers
- ❌ Deleting or weakening an ArchUnit rule
- ❌ Editing an acceptance criterion to match the code

Every fix is backed by a test that would fail without it, except SHF-4 where that limitation is explicitly disclosed above rather than papered over.

---

## Recurring Lesson (recorded for later milestones)

`@SpringBootTest` shares **one database across every integration-test class in a JVM fork**, not just across methods in a class. Any assertion of a bare `count(*)` on a shared table (`teams`, `categories`, `users`) becomes fragile the moment another suite member legitimately inserts a row.

This bit the project **three separate times** (Batch 4 teams/categories, and again here with the `ADMIN` user count). Correct patterns:

- assert **specific rows exist** by slug/email, or
- assert a **before/after delta** (which is also the semantically correct way to express "idempotent"),
- never a fixed grand total.

---

## Final Verdict

✅ **APPROVE** — all Blockers and all accepted Should-fix items are implemented and verified green on GitHub Actions with real PostgreSQL Testcontainers. The single deferred item (SHF-6) was rated benign by both reviewers, explicitly deferred by the user, and is tracked in the README for A2.

**Proceed to Phase 5 (E2E Testing).**
