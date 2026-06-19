---
id: '0010'
name: Adopt Spring Data JPA with H2 and Flyway
description: 'Persist via Spring Data JPA on H2 in PostgreSQL mode with Flyway as the sole schema owner, keeping a config-only path to hosted Postgres.'
status: accepted
date_proposed: '2026-06-18'
date_accepted: '2026-06-18'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- build
- persistence
---
# ADR-0010: Adopt Spring Data JPA with H2 and Flyway

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0010 |
| Name | Adopt Spring Data JPA with H2 and Flyway |
| Description | Persist via Spring Data JPA on H2 in PostgreSQL mode with Flyway as the sole schema owner, keeping a config-only path to hosted Postgres. |
| Status | accepted |
| Date proposed | 2026-06-18 |
| Date accepted | 2026-06-18 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | build, persistence |
<!-- adr-meta:end -->

## Context and Problem Statement

The API needs a persistence layer. We must choose a JPA provider and ORM, an in-process
database for development and testing, and a schema-management strategy. The key requirement
is that schema ownership is explicit and unambiguous from the start so we can evolve safely
to a hosted Postgres instance later without reworking migration files.

## Decision Drivers

- Spring Boot provides first-class JPA support that minimises boilerplate while remaining
  standard and portable.
- Deterministic schema evolution with a migration ledger is required from day one; auto-DDL
  generation whose output varies by Hibernate version is not acceptable.
- An in-process database allows integration tests to run without an external service,
  keeping CI fast and simple.
- Postgres parity must be maintainable: switching from the in-process database to a hosted
  Postgres instance must require only a datasource/driver change, not migration rewrites.

## Considered Options

- Spring Data JPA + H2 in-memory (PostgreSQL compatibility mode) + Flyway, with Postgres
  for production later
- Spring Data JPA + Testcontainers Postgres from day one
- Spring Data JDBC + H2 + Flyway

## Decision Outcome

Chosen option: **Spring Data JPA with H2 in-memory (PostgreSQL compatibility mode) for
development and testing, Flyway as the sole schema owner, and Postgres planned for
production**, because it gives us migration-ledger discipline immediately with zero external
service dependency, while keeping the Postgres upgrade path to a configuration-only swap.

All DDL lives in Flyway migration scripts under `src/main/resources/db/migration`. Hibernate's
`spring.jpa.hibernate.ddl-auto` is set to `validate` — Hibernate asserts that the live schema
matches the entity model but never generates or alters DDL. This makes the Flyway migration
ledger the single source of truth for schema state.

H2 is configured in PostgreSQL compatibility mode (`MODE=PostgreSQL`) so that SQL syntax in
migrations is as close to real Postgres as possible. When the team moves to a hosted Postgres
instance, the change is swapping the datasource URL and credentials (via the
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`
environment variables) and adding the PostgreSQL JDBC driver — plus Flyway's PostgreSQL module — to
the build. The JDBC driver is not pinned in configuration, so once that dependency is present Spring
Boot infers the driver from the URL; the Flyway migration files and Hibernate entity mappings are
unchanged. Only H2 ships on the classpath today, so the move is a configuration **and** dependency
change, not configuration alone.

Testcontainers was considered but deferred: the additional complexity and Docker-in-Docker
CI requirement is not justified at this stage, and H2 PostgreSQL mode is sufficient for the
current schema surface. Testcontainers remains available as a future upgrade if H2 fidelity
becomes a bottleneck. Spring Data JDBC was ruled out because the team's existing JPA
familiarity and the project's future feature complexity favour JPA's richer feature set.

### Consequences

- Good: Flyway-owned schema gives an auditable, version-controlled history of every
  structural change.
- Good: `ddl-auto=validate` catches entity/schema drift at startup rather than silently at
  query time.
- Good: Integration tests run against a real in-process database with the full migration
  history applied, giving high confidence without an external dependency.
- Good: Promoting to Postgres at deployment time needs only a configuration change plus adding
  the Postgres driver and Flyway Postgres module to the build — no migration rewrites.
- Bad: H2's PostgreSQL compatibility mode is not 100% faithful; any H2-unsupported SQL
  syntax must be worked around (e.g. using compatible equivalents or guarding with a
  H2-specific migration).
- Bad: Flyway migration files must be treated as immutable once merged to main; schema
  errors require a new corrective migration rather than an edit.

## Links

- ADR-0006: Adopt Java 25, Spring Boot, and Maven with Docker (establishes the Spring Boot
  baseline this ADR builds on)
