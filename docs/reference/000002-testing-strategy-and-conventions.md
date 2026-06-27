---
id: '000002'
title: Testing strategy and conventions
description: How tests are layered (e2e / integration / unit), which base classes they extend, and how the database is reset between tests
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
| Description | How tests are layered (e2e / integration / unit), which base classes they extend, and how the database is reset between tests |
| Tags | architecture, testing |
| Created | 2026-06-26 |
| Updated | 2026-06-26 |
| Related | com.zarlania.api.support |
<!-- ref-meta:end -->

## Overview

Tests are organized by what they prove, and that intent is encoded in the class-name suffix
and the base class. Each layer has one job; we don't re-prove a lower layer's job in a higher
one (an e2e test does not re-assert persistence, a service test does not re-assert HTTP).

## Scope

Covers the layering of automated tests, naming, the test base classes, and the between-test
database reset. It does not cover endpoint contracts (the springdoc OpenAPI is the source of
truth there, per ADR-0003) nor the production code structure.

## Rules / constraints

### Layers

- **Controller → end-to-end test** (`*EndToEndTest`, extends `AbstractEndToEndTest`). Boots
  the whole app on a random port and drives it over real HTTP with a `RestTestClient` bound to
  that server. Asserts the request/response contract and middleware — status codes, headers,
  validation, the global exception handler. It does **not** assert that data was persisted.
- **Service → integration test** (`*ServiceIntegrationTest`, extends `AbstractIntegrationTest`)
  **plus** a **service unit test** (`*ServiceUnitTest`). The integration test proves behavior
  against a real database (including that rows were actually written); the unit test (Mockito,
  extends nothing) covers branching/validation in isolation.
- **Repository → integration test** (`*RepositoryIntegrationTest`, extends
  `AbstractIntegrationTest`), and **only** when the repository declares custom query methods.
  A repository with nothing but inherited CRUD gets no test. Repositories are not unit-tested.
- Mapper / pure-logic tests are plain unit tests and extend nothing.

### Base classes

- `AbstractIntegrationTest` is **slice-agnostic**: it carries shared configuration only (the H2
  pin and the between-test cleanup) and does **not** impose a context-loading annotation. Each
  subclass brings its own slice — `@DataJpaTest` for the fast JPA slice (with `TestEntityManager`
  and per-test rollback) or `@SpringBootTest` for cross-domain orchestration. This keeps per-test
  speed while still sharing one base.
- `AbstractEndToEndTest` is uniform: `@SpringBootTest(webEnvironment = RANDOM_PORT)` with a
  server-bound `RestTestClient`.
- A unit test is free to extend nothing.

### Database reset

- `CleanDatabaseTestExecutionListener` (wired into both base classes) clears every application
  table after each test method, so committed rows never leak across the JVM-lifetime in-memory
  H2 instance.
- Tables are **discovered dynamically** from `INFORMATION_SCHEMA` (Flyway's history table
  excluded) — there is **no hand-maintained table list** to keep in sync as the schema grows.
- The reset runs on a single connection with referential integrity disabled, ordered after the
  test's transaction settles (so a `@DataJpaTest` rollback completes first).

## Related

- `com.zarlania.api.support` — the test base classes and the cleanup listener
- ADR-0010 (Spring Data JPA with H2 and Flyway) — the database the reset targets
- ADR-0003 (public OpenAPI) — the source of truth for endpoint contracts, which e2e tests exercise but do not document
