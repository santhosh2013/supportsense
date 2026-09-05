# SupportSense

AI-powered support ticket triage and resolution engine — Spring Boot 3.3 / Java 21 /
Spring AI 1.0 / PostgreSQL 16 + pgvector / Redis. This repository builds **milestone A1
only**: domain model, database schema, JWT auth, and asynchronous ticket ingestion.

SupportSense is a standalone project. It shares no code, no database, and no entities with
any other project. See `.github/artifactory/supportsense-a1-requirements.md` for full scope.

## Repository layout

```
api/          Spring Boot application (Maven)
web/          Angular console (added in milestone A6)
infra/        docker-compose.yml, Terraform (later)
eval/         AI evaluation golden set (added in milestone A6)
docs/         ADRs, architecture notes, metrics definitions
```

## Running locally

### No Docker required for the normal dev loop

Unit tests and `@WebMvcTest` slices run with `mvn test` and require **no Docker and no
external database**.

### Running the application locally

The `local` profile talks to a [Neon](https://neon.tech) free-tier PostgreSQL 16 instance
(supports `CREATE EXTENSION vector`). Set the connection string as an environment variable —
**never commit it**:

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://<your-neon-host>/supportsense?sslmode=require"
$env:SPRING_DATASOURCE_USERNAME = "<user>"
$env:SPRING_DATASOURCE_PASSWORD = "<password>"

cd api
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Running with Docker (when a container runtime is available)

```powershell
cd infra
docker compose up -d

cd ..\api
.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Corporate proxy notes

If Docker Desktop is installed behind a corporate proxy, configure the daemon's proxy
settings under **Settings → Resources → Proxies** (or the equivalent `daemon.json` entry),
and set `HTTP_PROXY` / `HTTPS_PROXY` for the Maven Wrapper if dependency downloads fail:

```powershell
$env:MAVEN_OPTS = "-Dhttp.proxyHost=<proxy> -Dhttp.proxyPort=<port>"
```

## Running the full test suite

Integration tests require Docker (Testcontainers, real PostgreSQL — never H2) and run in
CI on every pull request:

```powershell
cd api
.\mvnw verify
```

If Docker is unavailable locally, this only needs to succeed in GitHub Actions — see
`.github/workflows/ci.yml`. A red build publishes the Surefire/Failsafe reports as an
artifact so it is diagnosable without reproducing it locally.

## Architecture

See `.github/artifactory/supportsense-a1-design.md` for the full design and
`docs/adr/README.md` for the decision log.

- **Pattern:** feature-sliced modular monolith with a pure domain core, enforced by ArchUnit
- **Ingestion:** persist-first — the ticket row is committed before async dispatch, so a
  restart or a rejected task never loses work (ADR-0011)
- **Team isolation:** enforced once, in the repository layer, for every ticket read
  (ADR-0006)

## Non-negotiables

1. Every business rule enforced server-side and covered by a test
2. RFC-7807 `ProblemDetail` on every error
3. Flyway migrations, `ddl-auto=validate`
4. Testcontainers, never H2
5. No library outside the locked technology stack
6. No secrets in source
