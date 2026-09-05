### Verdict
REQUEST CHANGES

### Blocker findings
1. Missing orphan fallback path for long-lived NEW tickets (>1h)
- Location: [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java#L85), [api/src/main/java/io/github/santhosh2013/supportsense/common/config/SupportSenseProperties.java](api/src/main/java/io/github/santhosh2013/supportsense/common/config/SupportSenseProperties.java#L32)
- Description: The requirements/design/checkpoint specify fallback routing when either attempts are exhausted or status NEW ages past 1 hour. The implementation only reaps stale PROCESSING rows and routes exhausted attempts; there is no scan/branch using untriagedOrphanThreshold for NEW tickets.
- Why Blocker: This can pass CI while still being functionally wrong. A ticket can remain NEW with team_id NULL indefinitely, violating the orphan-prevention rule and making it invisible to human agents under team isolation.

2. Bulk ingestion endpoint is missing from implementation
- Location: [api/src/main/java/io/github/santhosh2013/supportsense/ticket/web/TicketController.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/web/TicketController.java#L39)
- Description: Controller exposes only POST /api/tickets and two GETs. There is no POST /api/tickets/bulk endpoint, no bulk request/response model, and no corresponding test coverage.
- Why Blocker: This is a required milestone behavior in requirements and execution plan. CI can still pass if tests never assert it, but the delivered behavior is incomplete.

### Should-fix findings
1. JWT algorithm is not explicitly pinned to HS256 in code
- Location: [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtTokenService.java#L37), [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtTokenService.java#L43)
- Description: Token creation uses signWith(signingKey) and parsing uses verifyWith(signingKey)/parseSignedClaims, but there is no explicit allowed-algorithm assertion for HS256.
- Why Should-fix: Current usage is likely safe against none-tokens because parseSignedClaims requires a signed JWS, but the requirement asks for explicit algorithm pinning. Make the check explicit so policy cannot drift accidentally.

2. Coverage exclusions are broad enough to hide meaningful behavior
- Location: [api/pom.xml](api/pom.xml#L211), [api/pom.xml](api/pom.xml#L212)
- Description: JaCoCo excludes all classes under config. This excludes non-trivial behavior such as required-secret fail-fast validation and async failure wiring, not just passive wiring.
- Why Should-fix: This can inflate confidence and obscure regressions in operationally critical logic.

3. No explicit negative test proving AFTER_COMMIT listener does not run on rollback
- Location: [api/src/test/java/io/github/santhosh2013/supportsense/ticket/TicketIngestionAfterCommitIT.java](api/src/test/java/io/github/santhosh2013/supportsense/ticket/TicketIngestionAfterCommitIT.java#L57)
- Description: The test proves committed rows reach DONE, but there is no paired test that forces rollback and asserts no dispatch/event handling occurs.
- Why Should-fix: The positive path is strong, but a rollback non-dispatch assertion would close a high-risk regression gap for event timing.

### Nice-to-have findings
1. Development/test credentials are literal placeholders in source-controlled config
- Location: [api/src/main/resources/application-local.yml](api/src/main/resources/application-local.yml#L8), [api/src/main/resources/application-local.yml](api/src/main/resources/application-local.yml#L13), [api/src/main/resources/application-local.yml](api/src/main/resources/application-local.yml#L14), [api/src/test/resources/application-test.yml](api/src/test/resources/application-test.yml#L6), [api/src/test/resources/application-test.yml](api/src/test/resources/application-test.yml#L7), [infra/docker-compose.yml](infra/docker-compose.yml#L9)
- Description: Values appear to be non-production placeholders, but they still look like credentials/secrets to scanners and reviewers.
- Why Nice-to-have: No active production secret exposure was found, but using explicit fake placeholders or env-only defaults would reduce scanner noise and policy friction.

### Proxy-boundary sweep — full inventory
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AdminPasswordBootstrapRunner.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AdminPasswordBootstrapRunner.java#L42) run(ApplicationArguments) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java#L47) register(RegisterRequest) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java#L74) login(LoginRequest) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java#L91) refresh(String) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/AuthService.java#L102) currentUser(String) - @Transactional(readOnly=true) - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java#L50) issueNewFamily(User) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java#L55) rotate(String) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java#L60) sweep() - @Scheduled - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/IngestionSweepService.java#L84) reap() - @Scheduled + @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/OrphanTicketService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/OrphanTicketService.java#L34) routeToFallbackTeam(Ticket) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketDispatchListener.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketDispatchListener.java#L26) onTicketCreated(TicketCreatedEvent) - @TransactionalEventListener(AFTER_COMMIT) - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java#L36) dispatch(Long) - @Async - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java#L58) recordFailure(Long,String) - @Transactional - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketInsertAttempt.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketInsertAttempt.java#L35) tryInsert(CreateTicketRequest) - @Transactional(REQUIRES_NEW) - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java#L36) listVisibleTickets(String,Pageable) - @Transactional(readOnly=true) - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java#L44) getVisibleTicket(String,Long) - @Transactional(readOnly=true) - SAFE
- [api/src/main/java/io/github/santhosh2013/supportsense/ticket/persistence/TicketRepository.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/persistence/TicketRepository.java#L43) claimForProcessing(Long,Instant) - @Transactional - SAFE

Sweep summary for bug classes:
- SELF-INVOKED-BUG: none found
- PRIVATE-OR-FINAL-BUG: none found
- CONSTRUCTOR-CALL-BUG: none found

### Mandatory-check results
1. Proxy-boundary sweep: Pass with inventory complete. All methods with @Transactional, @Async, @Scheduled, @TransactionalEventListener were traced. No self-invocation, no private/final proxy-target methods, and no constructor/@PostConstruct invocation of these annotated methods were found.

2. Transaction correctness: Partial pass. AFTER_COMMIT usage is correct in implementation (event published in REQUIRES_NEW insert transaction and consumed by @TransactionalEventListener(AFTER_COMMIT): [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketInsertAttempt.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketInsertAttempt.java#L35), [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketDispatchListener.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketDispatchListener.java#L25)). Refresh-token family revocation uses a genuine independent transaction via TransactionTemplate PROPAGATION_REQUIRES_NEW, not self-invocation ([api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java#L45)). Claiming is done in repository transaction and async dispatch submission is separate; no interleaving bug found. Missing piece: no explicit rollback non-dispatch test.

3. Concurrency: Pass. Conditional claim uses rowcount semantics and worker gates on claimed==0 ([api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java#L41), [api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketIngestionWorker.java#L42)). Concurrency IT correctly asserts the sum of affected rows equals 1, not merely no exception ([api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionClaimConcurrencyIT.java](api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionClaimConcurrencyIT.java#L77)).

4. Three specific behavior checks: Mixed. Attempt-cap fallback path is implemented and tested ([api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/OrphanTicketService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/OrphanTicketService.java#L34), [api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionReaperIT.java](api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionReaperIT.java#L127)); however, the separate NEW older than 1 hour orphan rule is not implemented (Blocker). Executor saturation test genuinely saturates with latch-blocking and tiny pool/queue before POST assertion ([api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionRejectionIT.java](api/src/test/java/io/github/santhosh2013/supportsense/ticket/IngestionRejectionIT.java#L79)). Pre-screen and category block combine as OR and short-circuit downstream continuation as required ([api/src/main/java/io/github/santhosh2013/supportsense/triage/domain/AutoAnswerGate.java](api/src/main/java/io/github/santhosh2013/supportsense/triage/domain/AutoAnswerGate.java#L27), [api/src/test/java/io/github/santhosh2013/supportsense/triage/domain/AutoAnswerGateTest.java](api/src/test/java/io/github/santhosh2013/supportsense/triage/domain/AutoAnswerGateTest.java#L45)).

5. Security: Mixed. Signature verification occurs before claims are used in filter path, and expired/invalid JWTs are rejected via JwtException handling ([api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtAuthenticationFilter.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/JwtAuthenticationFilter.java#L40)). Refresh token rotation/replay family revocation is implemented and covered ([api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java](api/src/main/java/io/github/santhosh2013/supportsense/auth/app/RefreshTokenService.java#L94), [api/src/test/java/io/github/santhosh2013/supportsense/auth/AuthFlowIT.java](api/src/test/java/io/github/santhosh2013/supportsense/auth/AuthFlowIT.java#L116)). Team isolation returns 404 (not 403) and is tested ([api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java](api/src/main/java/io/github/santhosh2013/supportsense/ticket/app/TicketService.java#L53), [api/src/test/java/io/github/santhosh2013/supportsense/ticket/TicketApiSecurityIT.java](api/src/test/java/io/github/santhosh2013/supportsense/ticket/TicketApiSecurityIT.java#L52)). Gap: HS256 is not explicitly pinned in code.

6. Coverage honesty: Mixed. Coverage floor remains 60% ([api/pom.xml](api/pom.xml#L32)); no @Disabled/@Ignore found in tests. But JaCoCo excludes all config classes ([api/pom.xml](api/pom.xml#L211)), which hides meaningful behavior, not only boilerplate wiring. I did not find tests that only assert getters/setters/toString.

7. Repository hygiene: Partial pass. I found no employer name, internal hostname, corporate email pattern, proxy address, or CDSID-style identifier leaked as real internal data in the requested docs/config/code/tests scope. I did find literal local/test placeholder credentials and compose defaults ([api/src/main/resources/application-local.yml](api/src/main/resources/application-local.yml#L8), [api/src/test/resources/application-test.yml](api/src/test/resources/application-test.yml#L6), [infra/docker-compose.yml](infra/docker-compose.yml#L9)). I could not verify historical leaks because this review did not inspect full git history diffs.

### Uncertainty / low-confidence areas
- I could not read historical commits and deleted content for past secret exposure; this review is limited to the current working tree.
- I did not execute the full runtime suite during this review; conclusions are from source and test inspection.
- The required agent instruction file path agents/sdlc/blueprint-code-review.agent.md was not present in this workspace, so I could not cross-check additional mode-specific process details from that file.

### Approach Recommendation
Primary review not available for comparison — recommendation deferred to orchestrator.
