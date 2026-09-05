# Architecture Decision Records — SupportSense

Written as decisions are made, never retrospectively. Superseded ADRs are marked
`Superseded`, not deleted — the reasoning trail is the valuable part.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-customer-tier-snapshot.md) | `customer_tier` as a point-in-time snapshot | Accepted |
| [0002](0002-async-rejection-policy.md) | `AbortPolicy` over `CallerRunsPolicy` | **Superseded** by 0015 |
| [0003](0003-dual-ai-provider-profiles.md) | Dual AI provider profiles | Accepted |
| [0004](0004-db-invariants-over-app-checks.md) | Database-enforced invariants over application checks | Accepted |
| [0005](0005-defer-api-key-auth.md) | Defer API-key authentication | Accepted |
| [0006](0006-404-over-403-for-team-isolation.md) | 404 over 403 for team isolation | Accepted |
| [0007](0007-testcontainers-ci-first.md) | Testcontainers, CI-first execution | Accepted |
| [0008](0008-neon-for-local-profile.md) | Neon for the local profile | Accepted |
| [0009](0009-prescreen-before-llm.md) | Deterministic pre-screen before the LLM | Accepted |
| [0010](0010-package-by-feature.md) | Package by feature, not by layer | Accepted |
| [0011](0011-persist-first-ingestion.md) | Persist-first ingestion with sweep and reaper | Accepted |
| [0012](0012-ingestion-state-separate-from-status.md) | `ingestion_state` separate from business `status` | Accepted |
| [0013](0013-pure-domain-core-and-injected-clock.md) | Pure domain core enforced by ArchUnit + injected clock | Accepted |
| [0014](0014-merged-jacoco-coverage.md) | JaCoCo merged unit + integration coverage | Accepted |
| [0015](0015-rejection-returns-202.md) | Rejection returns 202 + counter | Accepted (supersedes 0002) |
