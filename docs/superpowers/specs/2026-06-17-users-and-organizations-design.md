# Users and Organizations — Design

**Date:** 2026-06-17
**Status:** Draft — not yet implemented

This spec introduces the first real domains in the service: `users` and `organizations`,
plus a repo-wide **reference-docs system** whose first authored doc captures the user/org
rules. It is split into **three implementation phases**, each its own plan / merge / release,
so the work divides cleanly across context windows. Update the Status table below as phases
land.

## Phase status

| Phase | Scope | State |
|-------|-------|-------|
| 1 | Persistence foundation + `users` domain | Not started |
| 2 | `organizations` domain | Not started |
| 3 | Reference-docs system + first doc (user/org rules) | Not started |

## Context & goals

Organizations are the ownership root of this service: data built into the system is tied to
an organization. A user gets a one-to-one **personal organization** on account creation;
users may also own/join **general organizations** (company accounts). Authentication,
secrets, and the orchestration that ties account creation together are explicitly **out of
scope** and will land later in a separate `identity` domain.

The goal of this work is to build the two foundational building-block domains — `users` and
`organizations` — as focused, independently testable units that a future `identity`
orchestrator will coordinate.

### Requirements (from product intent)

Personal accounts / organizations:

- A user can own **exactly one** personal organization.
- A personal organization has **no members besides its owner**.
- User ↔ personal organization is **1:1**.
- A user's **email is unique** (one account per email).

General organizations (company accounts):

- A user can create general organizations and becomes an **owner**.
- Owning a general org does not affect ownership of the personal org (a user can own 2+ orgs).
- A general org can have **multiple owners**.
- A general org can have **members that are not owners**.
- **Every organization always has at least one owner** — a `PERSONAL` org has exactly one,
  a `GENERAL` org has one or more. No organization may ever reach zero owners.

Authentication note: the `User` entity holds the **email association only — no secrets**
(no password hashes, tokens, or refresh tokens). Those live in the future `identity` domain.

## Scope

**In scope (this spec):**

- A persistence foundation: Spring Data JPA over an H2 in-memory database with Flyway-managed
  schema, structured so the move to Postgres is a datasource/dependency swap (no entity,
  repository, or migration changes).
- The `users` domain: entity, repository, DTO, service, tests.
- The `organizations` domain: `Organization` + `Membership` entities, repositories, DTOs,
  service with all invariants, tests.
- A repo-wide **reference-docs system** (`docs/reference/` + `./scripts/ref`), and its first
  authored doc capturing the user/org association rules.
- Four ADRs (see below).

**Out of scope (deliberately deferred):**

- **HTTP endpoints / controllers.** We know we must be able to create users and
  organizations, but we do **not** know the request/response contracts because the use cases
  (driven by `zarlania-app` and the `identity` orchestrator) don't exist yet. Building
  controllers now would invent action contracts prematurely. The service layer is built to
  satisfy the requirements; controllers arrive when a real consumer needs them.
- **Cross-domain orchestration.** "Register an account" = create a user *and* their personal
  org atomically is a coordination concern that belongs to the future `identity` domain.
  Neither `users` nor `organizations` reaches into the other to create its entities.
- **Cross-domain user-existence validation.** `organizations` stores a `userId` without
  checking that the user exists. Where the integrity/permission gates belong depends on the
  orchestration layer, which isn't designed yet — so we defer the gate rather than guess its
  home (YAGNI). Revisited when the orchestration layer is designed.
- **Authentication / identity / secrets.**

## Architecture decisions (ADRs to create)

These are created during implementation via the `adr-create` skill. Run `./scripts/adr check`
after each.

1. **Adopt Spring Data JPA with H2 + Flyway (in-memory now, Postgres later).** Persistence
   model + new major dependencies (`spring-boot-starter-data-jpa`, H2, `flyway-core`). H2 runs
   in-memory at runtime for this first pass and backs integration tests; **schema is managed by
   Flyway migrations** (Hibernate `ddl-auto=validate`, not auto-generated), so the same
   migrations run on H2 now and Postgres later. Switching to Postgres is a datasource + driver
   change with no change to entities, repositories, or migrations. (Phase 1.)
2. **Domain-boundary convention.** Repo-wide, governs every future domain: a domain uses its
   JPA entities internally; **anything crossing a domain or API boundary is a DTO, never an
   entity**; **no cross-domain entity associations** — a domain references another domain's row
   by opaque id and never imports or loads another domain's entity (so no `@ManyToOne` across
   domains). A real **database foreign key** *is* still declared (in a migration) to protect
   referential integrity at rest — that is a data-layer concern, distinct from ORM coupling.
   Cross-domain interaction is an **in-process method call, never an internal HTTP hop** (all
   domains co-deploy in one service). (Phase 1.)
3. **Adopt Lombok (with usage convention).** New repo-wide build-time dependency. Used to cut
   boilerplate on **entities only** (`@Getter`/`@Setter`/`@NoArgsConstructor`, etc.);
   **`@Data` and `@EqualsAndHashCode` are forbidden on JPA entities** (relational-entity
   identity/lazy-loading footgun); DTOs are Java `records` and use no Lombok. (Phase 1.)
4. **Record explanatory reference docs as numbered living guides.** Establishes the
   reference-docs system (parallel to ADR-0001 for ADRs): `docs/reference/` holds living,
   editable explanations of how the system works, 6-digit-numbered with a tag registry,
   generated index, and `./scripts/ref` tooling. Distinct from ADRs (immutable decisions) and
   superpowers specs (implementation-time). Additive; supersedes nothing. (Phase 3.)

No existing ADR is contradicted; this is additive to ADR-0001 (MADR ADRs) and ADR-0006
(Java 25 / Spring Boot / Maven).

## Cross-cutting: persistence, auditing, Lombok

### Persistence configuration

- Add `spring-boot-starter-data-jpa`, the H2 driver, and `flyway-core`.
- Runtime: H2 in-memory datasource (this first pass). Configuration is structured so a
  Postgres datasource URL + driver is the only change required later (the Postgres move also
  adds `flyway-database-postgresql`).
- **Schema is owned by Flyway migrations** (`src/main/resources/db/migration`), not Hibernate.
  Hibernate runs with `ddl-auto=validate` so the mapped entities are checked against the
  migrated schema. The same migrations run on H2 now and Postgres later, and integration tests
  run against the real migrated schema (constraints included).
- This lets a real **foreign key** (e.g. `membership.user_id` → `users.id`) be declared in a
  migration to protect data at rest, while the entity keeps a plain `userId` column with no
  cross-domain association (see ADR-2).

### Auditing timestamps

Every persisted entity carries `createdAt` and `updatedAt` timestamps stored at full
(sub-millisecond) precision — no truncation.

- A shared `Auditable` JPA mapped-superclass holds `createdAt` (`@CreatedDate`) and
  `updatedAt` (`@LastModifiedDate`) as `Instant`.
- Enable with `@EnableJpaAuditing`; use the default time source (no custom truncating
  `DateTimeProvider`).
- Migration columns are `TIMESTAMP(6)` (microsecond) so H2 now and Postgres later agree on
  precision — keeping more than milliseconds, which is effectively free.

### Lombok conventions

- Entities: `@Getter`, `@Setter`, `@NoArgsConstructor` (JPA requires a no-arg constructor),
  and constructors/builders where they reduce boilerplate.
- **Never** `@Data` or `@EqualsAndHashCode` on a JPA entity.
- DTOs are records — no Lombok.
- Add `lombok.config` at repo root with `lombok.addLombokGeneratedAnnotation = true` so
  JaCoCo ignores generated methods (keeps the ≥ 80% coverage gate honest).
- Verify Lombok annotation processing is wired in the Maven compiler config and coexists with
  Spotless (google-java-format), Checkstyle, and SpotBugs.

## Domain design

### `com.zarlania.api.users`

- **`User`** (entity, extends `Auditable`): `id` (UUID), `email` (unique, not null). No
  secrets, no organization knowledge.
- **`UserRepository`** (`JpaRepository<User, UUID>`): `findByEmail`.
- **`UserDto`** (record): `id`, `email`. The only `User` shape any other domain or consumer
  ever sees.
- **`UserService`**:
  - `create(email)` → `UserDto`. Enforces email uniqueness (DB unique constraint, surfaced as
    a meaningful domain failure rather than a raw constraint-violation leak).
  - `findById(id)` → `Optional<UserDto>`.
  - `findByEmail(email)` → `Optional<UserDto>`.

### `com.zarlania.api.organizations`

- **`Organization`** (entity, extends `Auditable`): `id` (UUID), `name`, `type` (enum
  `OrganizationType` = `PERSONAL` | `GENERAL`).
- **`Membership`** (entity, extends `Auditable`): `id` (UUID), `organization` (`@ManyToOne`,
  same-domain association), **`userId` (plain UUID column — *not* a JPA relationship to
  `User`)**, `role` (enum `MembershipRole` = `OWNER` | `MEMBER`). The plain `userId` column is
  the domain boundary: in code, organizations references a user by id and never imports or
  loads the `User` entity. At the database layer a real **foreign key** `membership.user_id` →
  `users.id` is declared in a Flyway migration to protect referential integrity at rest (see
  ADR-2) — the constraint exists without any ORM association.
- **`OrganizationRepository`**, **`MembershipRepository`**.
- **`OrganizationDto`** (record): `id`, `name`, `type`. **`MembershipDto`** (record):
  `organizationId`, `userId`, `role`.
- **`OrganizationService`**:
  - `createPersonalOrganization(ownerUserId, name)` → `OrganizationDto`. Creates a `PERSONAL`
    org and a single `OWNER` membership for `ownerUserId`. As the **sole creator** of personal
    orgs it enforces the uniqueness half of the 1:1 rule — **rejects creating a second** for an
    owner who already has one. (The "always create one" half lives in the deferred
    account-creation orchestration, so together they yield **exactly one** personal org per
    user.)
  - `createGeneralOrganization(creatorUserId, name)` → `OrganizationDto`. Creates a `GENERAL`
    org and an `OWNER` membership for `creatorUserId`.
  - `addMember(orgId, userId)` → `MembershipDto`. Adds a `MEMBER`. **Rejected for `PERSONAL`
    orgs** (personal orgs allow no member beyond the owner).
  - `addOwner(orgId, userId)` → `MembershipDto`. Adds or promotes a user to `OWNER`.
    **Rejected for `PERSONAL` orgs.**
  - Reads as needed: org by id, memberships of an org.

### Invariants (enforced in the service layer, proven by tests)

- A user owns **exactly one** `PERSONAL` organization (1:1 with the user). This domain
  enforces the uniqueness half (no second); the account-creation orchestration guarantees the
  one always exists.
- A `PERSONAL` organization has exactly one membership: its owner (exactly one owner, no
  other members).
- A `GENERAL` organization may have multiple `OWNER` and multiple `MEMBER` memberships.
- **Every organization has at least one `OWNER` at all times** — exactly one for `PERSONAL`,
  one or more for `GENERAL`. No organization may reach zero owners. Any operation that could
  remove or demote an owner (none exist in this pass; e.g. a future `removeMember` /
  owner-demotion) must reject the change when it would leave the org ownerless.
- Email is unique across users.

### Boundary summary

`users` and `organizations` are independent **in code**: neither creates the other's
entities, and `organizations` has no compile-time dependency on `users` (it stores `userId`
opaquely, never imports `User`). All public service methods accept and return DTOs / ids —
never entities. The only link between them is the **database** FK `membership.user_id` →
`users.id`, which lives in a migration, not in the object model.

## Reference-docs system (Phase 3)

A repo-wide system for **living explanatory documentation** — how the system behaves — kept
distinct from ADRs (immutable decisions), superpowers specs/plans (implementation-time), and
the `docs/ai-prompts/` scratchpad. It mirrors the ADR tooling in shape; the docs differ in
that they are **living and editable**, not decision records.

### Directory `docs/reference/`

Parallel to `docs/adrs/`: `_template.md`, `_tags.md` (its own tag registry), a generated
`README.md` index, and `NNNNNN-kebab.md` files (**6-digit zero-padded** id + kebab slug). The
first authored doc is `000001-user-organization-association-rules.md`.

### Document shape (living, minimal frontmatter)

```yaml
id: "000001"
title: User–organization association rules
description: One-line summary
tags: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
related: []   # links to ADRs, code, or other reference docs
```

Plus a rendered meta block (between markers, like ADRs). Body is explanation-shaped:
**Overview → Scope → the rules/constraints → Related.** No proposed/accepted/superseded
lifecycle — docs are updated in place as the system evolves.

### `./scripts/ref` CLI

Same verbs as `adr` minus the decision lifecycle: `new`, `list`, `find`, `show`, `tags`,
`add-tag`, `tag-usage`, `by-tag`, `index`, `check`. No `accept` / supersede.

### Shared core (DRY)

Extract the generic logic out of `adr_tool` into a shared module: frontmatter parse/dump,
numbered-doc iteration, tag-registry load/add (sorted), index-table generation, id + slug
allocation with a **configurable zero-pad width** (4 for ADRs, 6 for reference), meta-block
rendering, and validation primitives. `adr_tool` is refactored onto the shared core — its
existing pytest suite guards the refactor — and the new `ref_tool` supplies its own schema,
columns, width, and commands. Each tool keeps only what is unique to it. The new tool ships
with its own pytest tests.

### Validation (`./scripts/ref check`)

Unique 6-digit ids, filename matches `id-slug`, required frontmatter present, tags known and
sorted, `updated ≥ created`, and the index up to date. Wired into `scripts/check` and
`.pre-commit-config.yaml` alongside the existing ADR hook.

### Governance touchpoints

- The 4th ADR establishes the system (see Architecture decisions).
- **CLAUDE.md** gains a short pointer (in the spirit of "Working with ADRs"): `docs/reference/`
  holds living explanations; use `./scripts/ref`, don't hand-scan; Claude decides when to
  consult or author one. No content lives in CLAUDE.md.
- **README.md** gains a human-facing "About `docs/reference/`" section and a line under "How
  the repo is organized."

Skills (an `adr-create` analog for reference docs) are **out of scope** — YAGNI; the CLI plus
the CLAUDE.md pointer suffice.

### First doc: user/org association rules

`000001-user-organization-association-rules.md` explains (does not decide) the rules and
constraints established by this spec: personal vs general organizations; **exactly one**
personal org per user; every org always has **≥ 1 owner** (personal exactly one, general one
or more); a personal org has only its owner; general orgs may have multiple owners and
members; email uniqueness; and the user↔org relationship. It links to the relevant ADRs and
code rather than restating decisions.

## Testing

- **Integration tests** run the real JPA layer against **H2 in-memory** and assert behavior
  through the public **service** surface: user creation + email-uniqueness rejection,
  personal/general org creation, and each invariant (second personal org rejected, member
  rejected on a personal org, multiple owners/members allowed on a general org).
- **Unit tests** cover pure logic that needs no database (invariant guards, DTO mapping),
  using **Mockito** for collaborators.
- **AssertJ** for all assertions across both layers.
- The existing ≥ 80% line/branch JaCoCo gate applies; Lombok-generated code is excluded via
  `lombok.config`.

## Phase plans

Each phase is implemented from its own plan in a clean session, and is a single merge → single
SemVer release. Phases 1–2 add functionality (`release:minor`); Phase 3 is tooling + docs with
no app-behavior change (`release:patch`).

### Phase 1 — Persistence foundation + `users` domain

1. Add dependencies: `spring-boot-starter-data-jpa`, H2, `flyway-core`, Lombok; add
   `lombok.config`.
2. Create the three ADRs (persistence, domain-boundary convention, Lombok).
3. Persistence/JPA config: H2 in-memory datasource (runtime + test), Hibernate
   `ddl-auto=validate`, Flyway enabled (`db/migration`).
4. Auditing infrastructure: `Auditable` mapped-superclass, `@EnableJpaAuditing` (default time
   source), `TIMESTAMP(6)` audit columns.
5. First Flyway migration: `users` table (id, unique email, audit columns).
6. `users` domain: `User`, `UserRepository`, `UserDto`, `UserService`.
7. Tests: integration (H2, real migrated schema) for create/uniqueness/lookups; unit for pure
   logic.

**Done when:** the `users` domain creates and looks up users with enforced email uniqueness
and audit timestamps, all gates green, ADRs accepted. Update the Phase status table.

### Phase 2 — `organizations` domain

1. Flyway migration: `organizations` and `memberships` tables, including the FK
   `memberships.user_id` → `users.id`.
2. `Organization` + `Membership` entities (extending `Auditable`), enums, repositories.
3. `OrganizationDto`, `MembershipDto`.
4. `OrganizationService`: personal/general creation, `addMember`, `addOwner`, reads, and all
   invariant enforcement.
5. Tests: integration (H2, real migrated schema) covering every invariant; unit for pure logic
   with Mockito.

**Done when:** all organization invariants hold and are proven by tests, all gates green.
Update the Phase status table.

### Phase 3 — Reference-docs system + first doc

1. Extract a shared core module from `adr_tool` (frontmatter, numbered-doc iteration, tag
   registry, index generation, id/slug allocation with configurable width, meta-block,
   validation); refactor `adr_tool` onto it with its pytest suite green and ADR behavior
   unchanged.
2. Build `ref_tool` + `./scripts/ref` launcher: living-doc schema, 6-digit width, commands
   (`new`/`list`/`find`/`show`/`tags`/`add-tag`/`tag-usage`/`by-tag`/`index`/`check`); add its
   pytest tests.
3. Scaffold `docs/reference/`: `_template.md`, `_tags.md`, generated `README.md` index.
4. Wire `./scripts/ref check` into `scripts/check` and `.pre-commit-config.yaml`.
5. Create the 4th ADR (reference-docs system).
6. Add the CLAUDE.md pointer and the README.md "About `docs/reference/`" section + org-layout
   line.
7. Author `000001-user-organization-association-rules.md`.

**Done when:** `./scripts/ref` scaffolds, indexes, and validates reference docs; the ADR
tooling still passes its tests on the shared core; the first doc exists and `check` passes;
all gates green. Update the Phase status table.

## Future work (not this spec)

- `identity` domain: account-creation orchestration (user + personal org atomically),
  membership/permission gates, secrets, authentication.
- HTTP endpoints, designed against real `zarlania-app` use cases.
- Postgres datasource swap (add `flyway-database-postgresql`); the Flyway migrations written
  in this spec carry over unchanged.
- Reference-docs authoring skills (an `adr-create` analog), if the CLI + CLAUDE.md pointer
  prove insufficient.

## Links

- Source intent: `docs/ai-prompts/users-and-organizations.md` (user scratchpad; not law).
