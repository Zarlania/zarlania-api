---
id: '000002'
title: Testing strategy and conventions
description: How tests are layered (controller / service / repository), the two suites (parallel vs serial transactional), base classes, isolation, and how tests run in parallel
tags:
- architecture
- testing
created: '2026-06-26'
updated: '2026-06-26'
related:
- com.zarlania.api.support
---
# Testing strategy and conventions

<!-- ref-meta:start -->
| Field | Value |
| --- | --- |
| ID | 000002 |
| Title | Testing strategy and conventions |
| Description | How tests are layered (controller / service / repository), the two suites (parallel vs serial transactional), base classes, isolation, and how tests run in parallel |
| Tags | architecture, testing |
| Created | 2026-06-26 |
| Updated | 2026-06-26 |
| Related | com.zarlania.api.support |
<!-- ref-meta:end -->

## Overview

Tests are organized by what they prove, and that intent is encoded in the class-name suffix and
the base class. Each layer has one job; we don't re-prove a lower layer's job in a higher one (a
controller test does not re-assert persistence, a service test does not re-assert HTTP). Isolation
is by **transactional rollback** wherever possible, which keeps the bulk of the suite fast and
parallel-safe; the handful of tests that must commit for real are quarantined into a separate
serial suite.

## Scope

Covers the layering of automated tests, naming, the test base classes, the two execution suites,
and how isolation/parallelism work. It does not cover endpoint contracts (the springdoc OpenAPI is
the source of truth there, per ADR-0003) nor the production code structure.

## Rules / constraints

### Layers

- **Controller → MockMvc test** (`*Test`, e.g. `IdentityControllerTest`). `@SpringBootTest`
  `@AutoConfigureMockMvc` `@Transactional`: drives the full stack through `MockMvc` and asserts the
  request/response contract and middleware (validation, the global exception handler). Runs in the
  test's transaction and rolls back. It does **not** assert that data was persisted.
- **Service → integration test** (`*ServiceIntegrationTest`, extends `AbstractIntegrationTest`)
  **plus** a **service unit test** (`*ServiceUnitTest`, Mockito, extends nothing). The integration
  test proves behavior against a real database; the unit test covers branching/validation in
  isolation.
- **Repository → integration test** (`*RepositoryIntegrationTest`, extends
  `AbstractIntegrationTest`), and **only** when the repository declares custom query methods. A
  repository with nothing but inherited CRUD gets no test; repositories are not unit-tested.
- Mapper / pure-logic tests are plain unit tests and extend nothing.

### The transactional suite

- A test that must let the code under test **commit or roll back for real** — e.g. asserting an
  atomic operation actually undid its first step — cannot use a wrapping test transaction (the
  inner `@Transactional` would merely join it, and the post-condition would still see the row).
  Such a test is named **`*TransactionalTest`** and extends **`AbstractTransactionalTest`**.
- Because these commit, they can't be isolated by rollback. `CleanDatabaseTestExecutionListener`
  (wired into `AbstractTransactionalTest`) truncates every table after each method instead — tables
  are discovered dynamically from `INFORMATION_SCHEMA` (Flyway's history excluded), so the cleanup
  scales with the schema. Keep this suite **small and scenario-specific**.

### Base classes

- `AbstractIntegrationTest` is **slice-agnostic**: shared anchor only, no context-loading
  annotation. Subclasses bring their slice — `@DataJpaTest` (fast JPA slice, `TestEntityManager`,
  per-test rollback) or `@SpringBootTest` + `@Transactional` (cross-domain, rollback). Isolation is
  by rollback, so these are parallel-safe.
- `AbstractTransactionalTest` is `@SpringBootTest` + the truncation listener, for the committing
  `*TransactionalTest` suite.
- A unit test extends nothing.

### Suites, isolation, and parallelism

- **Two suites.** The default (parallel) suite is everything except `*TransactionalTest`; the serial
  suite is `*TransactionalTest`, run via the `transactional-tests` Maven profile. They are separate
  CI checks: `./mvnw verify` (parallel, gates + coverage) and `./mvnw test -Ptransactional-tests`
  (serial, no coverage gate — its coverage is small and gated by the main run).
- **Parallelism is process-level.** Surefire forks one JVM per core (`forkCount=1C`), and each fork
  gets its **own** in-memory database (`jdbc:h2:mem:zarlania-<forkNumber>`). Fixed-key tests
  therefore can't collide across forks, and the parallel suite has no committing tests, so nothing
  leaks within a fork. This is why committing tests are quarantined: they'd break both rollback
  isolation and cross-fork safety.
- **Datasource pin.** H2 is pinned centrally via the Surefire `spring.datasource.url` system
  property (`pom.xml`), which outranks any `SPRING_DATASOURCE_URL` env var — tests never touch a
  real database.
- **Real-port HTTP** fidelity (if ever needed) belongs in a future, separate snapshot suite, not in
  the controller MockMvc tests.

## Related

- `com.zarlania.api.support` — the test base classes and the cleanup listener
- ADR-0010 (Spring Data JPA with H2 and Flyway) — the database tests target
- ADR-0003 (public OpenAPI) — the source of truth for endpoint contracts, which controller tests exercise but do not document
