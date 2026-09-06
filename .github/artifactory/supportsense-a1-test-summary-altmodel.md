# SupportSense A1 E2E Alt-Model Test Summary

## Scope
Implemented a black-box, HTTP-only end-to-end journey suite under `src/test/java/io/github/santhosh2013/supportsense/e2ealt/` for the required six categories:
- negative-auth matrix
- idempotency (sequential and concurrent)
- bulk partial success
- async completion polling via `ingestionState`
- page envelope and size clamp
- OpenAPI contract drift (paths/methods + runtime status-code documentation)

No production files were modified.

## Files Added
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/NegativeAuthMatrixAltIT.java`
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/TicketIdempotencyJourneyAltIT.java`
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/BulkIngestionJourneyAltIT.java`
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/AsyncIngestionCompletionAltIT.java`
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/TicketListPageEnvelopeAltIT.java`
- `api/src/test/java/io/github/santhosh2013/supportsense/e2ealt/OpenApiContractDriftAltIT.java`

## Ground-Truth Verification Performed
Read and validated behavior from source before asserting:
- `SecurityConfig` has no custom `AuthenticationEntryPoint`/`AccessDeniedHandler`; `/v3/api-docs` and `/v3/api-docs/**` are explicitly permitAll.
- `JwtAuthenticationFilter` clears context on parse failures and does not directly emit a response.
- `TicketController` enforces bulk max 500 and size clamp to 100.
- `BulkIngestionService` validates each item independently and returns ACCEPTED/DUPLICATE/REJECTED per-item outcomes.
- `TicketService` + `TicketSpecifications.visibleTo` enforce 404 non-leakage for invisible tickets.
- `TicketResponse` includes read-only `ingestionState` for polling.

## Journey Coverage Matrix
1. Negative-auth matrix: WRITTEN
2. Idempotency (BR-A02): WRITTEN
3. Bulk partial success: WRITTEN
4. Async completion via `ingestionState`: WRITTEN
5. Page envelope/list behavior: WRITTEN
6. OpenAPI contract drift: WRITTEN

## Black-box Limits / Honest Constraints
- True cross-team invisibility setup was not constructed because there is no HTTP endpoint to discover valid team IDs. The negative-auth matrix uses the allowed fallback: untriaged ticket invisibility to a fresh team-less AGENT.
- For 501-item bulk rejection, full proof that all 501 were not persisted is not externally enumerable via current API surface (no query by externalRef). The suite performs best-effort external verification by checking representative marker refs are absent from list output.

## Build / Execution
- Test compile command: `./mvnw.cmd -q -B test-compile`
- Result: PASS
- Docker availability check (`Get-Service ...; where.exe docker`): docker binary not found on host
- Integration test execution with Testcontainers (`mvn verify -Dtest=...`): NOT RUN due to missing Docker runtime

## Expected Runtime Negative-Auth Statuses (derived from source + existing behavior)
The suite asserts the following HTTP statuses:
- Missing Authorization on protected endpoint: 403
- Expired JWT: 403
- Tampered-signature JWT: 403
- `alg:none` forged JWT: 403
- Wrong-role AGENT on `POST /api/tickets/bulk`: 403
- Refresh token replay after rotation (original token): 401
- Descendant refresh token after family revocation: 401
- Existing but invisible ticket (`GET /api/tickets/{id}`): 404

## OpenAPI Drift Signal Captured by Suite
`OpenApiContractDriftAltIT#observedNegativeAuthStatusesAreDocumented` compares observed runtime auth-failure statuses against documented response codes in `openapi.yaml`.
- This is designed to fail if runtime emits a status that is not declared for that endpoint (for example, runtime 403 while spec lists only 401 for a protected read).

## Approach Recommendation
Primary test summary not available for comparison — recommendation deferred to orchestrator.
