# ADR-0008: Neon for the local profile

**Status:** Accepted

## Context

Local development needs a working PostgreSQL 16 + pgvector instance, but no container
runtime is available on the development machine.

## Decision

The `local` Spring profile connects to a Neon free-tier instance (Postgres 16, supports
`CREATE EXTENSION vector`) via the `SPRING_DATASOURCE_URL` environment variable, with
`sslmode=require`. The connection string is never committed. `infra/docker-compose.yml`
still ships, using `pgvector/pgvector:pg16`, for use once a container runtime is available.

## Consequences

Local development is unblocked without requiring Docker Desktop or waiting on IT approval
for it. CI is unaffected — Testcontainers runs against an ephemeral container on the GitHub
Actions runner regardless of what the `local` profile points at. The trade-off is a
dependency on a third-party free tier for local iteration, mitigated by the docker-compose
file remaining ready to use the moment a container runtime is available.
