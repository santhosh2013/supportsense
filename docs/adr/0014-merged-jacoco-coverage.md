# ADR-0014: Merged JaCoCo unit and integration coverage

**Status:** Accepted

## Context

Maven Surefire runs unit and slice tests; Failsafe runs Testcontainers integration tests.
Default JaCoCo `prepare-agent` configuration instruments only the Surefire JVM, silently
discarding integration-test coverage. That produces a false low coverage number and creates
the temptation to lower the coverage gate rather than correct measurement.

## Decision

Use separate JaCoCo agents for Surefire and Failsafe, merge the two execution-data files,
and run `report` and the 60% line-coverage `check` against the merged data. Exclude only
configuration classes, DTO records, generated code, and the Spring Boot entrypoint — never
business logic solely to pass the gate.

## Consequences

The coverage figure represents exercised backend behavior across both local and CI-only
Testcontainers suites. Configuration is more involved, but the quality gate measures what it
claims to measure. If the project cannot reach 60% through real branch coverage, the build
must report that fact rather than pad tests, weaken exclusions, or lower the threshold.
