# Design: persistence foundation and domain package convention

- **Issue:** [#26](https://github.com/Zarlania/zarlania-api/issues/26)
- **Date:** 2026-07-25
- **Applies to:** `Zarlania/zarlania-api` only.
- **Spec chain:** 1 of 7 for the account-creation / login / authentication
  effort. No predecessor. Successor: spec 2, *users, personal organizations &
  core auth* (written after this one; it references this spec).

## The spec chain

The originating prompt (accounts, organizations, roles, tokens, and a themed
frontend across two repos) was decomposed into seven sub-projects, each with its
own spec → plan → implementation cycle:

1. **API: persistence foundation & package restructure** — this spec.
2. API: users, personal orgs & core auth — Argon2 credentials, registration
   with email verification, login, JWT access tokens, refresh-token families.
3. API: general orgs, roles & permissions — membership, org roles, adding
   members, org-switch token minting.
4. API: admin & machine tokens — system-admin roles, super-admin bootstrap,
   impersonation tokens, system tokens.
5. App: theming & landing page — light/dark theming without flash, SEO landing
   page. May proceed in parallel with 2–4.
6. App: auth flows — signup, email-verify, login pages, personal home. Needs 2.
7. App: org flows — create/switch org, org home, member management. Needs 3.

Specs 5–7 live in `zarlania-app`'s `docs/superpowers/specs/`.

## Purpose

Give the backend a production-shaped persistence layer — Postgres, versioned
SQL migrations, JPA — and the domain package convention every future feature
follows, before any domain code exists. Everything else in the chain stands on
this.

## Scope

Delivered by this sub-project:

- Postgres wiring: local via docker compose, production via Render, tests via
  Testcontainers.
- Flyway as the sole owner of schema, with the migration conventions documented.
- The domain + layer-sub-package convention, replacing the current flat
  feature-package rule, including the CLAUDE.md rewrite.
- Deletion of the `hello` scaffolding feature.
- A reference doc (via the `creating-reference-docs` skill) covering the
  persistence conventions and the free-tier database runbook note.

Explicitly out of scope: any domain code, any production migration or table,
the `BaseEntity` class (deferred to spec 2 — see *Testing*), Redis/caching
(deferred to its first consumer), email, and all of specs 2–7.

## Package convention

Domains are top-level packages under `com.zarlania.api`, each containing only
the layer sub-packages it needs:

```text
com.zarlania.api.<domain>.controllers    HTTP endpoints
com.zarlania.api.<domain>.services       business rules
com.zarlania.api.<domain>.repositories   Spring Data interfaces
com.zarlania.api.<domain>.entities       JPA entities (never leave the domain)
com.zarlania.api.<domain>.dtos           records crossing the domain boundary
```

- Entities are internal to their domain. Cross-domain references are plain
  foreign-key ID columns plus DTO lookups through the owning domain's service —
  never a mapped JPA relation across domains. Within a domain, JPA relations
  (including lazy loading) are allowed and encouraged where they fit.
- The goal is that lifting a domain out of the monolith later is minimal work:
  everything crossing a domain boundary already goes through DTOs.
- **Aggregating (parent) domains are allowed.** A parent domain may group
  sub-domains, each nested with its own layer sub-packages
  (`fruit.controllers`, `fruit.apple.controllers`, `fruit.orange.services`,
  …). Every sub-domain is a full domain boundary in its own right: FK ids +
  DTOs between sub-domains, exactly as between top-level domains. The parent
  level holds orchestration only — controllers/services composing its
  children's services — never entities.
- `com.zarlania.api.common` holds only domain-agnostic infrastructure (first
  occupant, in spec 2: the persistence base class). Anything with business
  meaning belongs in a domain.
- CLAUDE.md's Layout section and its "organised by feature, not by layer" rule
  are rewritten to this convention. The spirit survives — no app-wide
  `controllers/` package — the letter changes: layers exist as sub-packages
  *inside* a domain.
- The `hello` feature (controller, response record, test) is deleted. Real
  domains arrive in spec 2.

## Persistence architecture

Chosen approach: **Flyway + Spring Data JPA** (over Liquibase, whose database
abstraction buys nothing for a Postgres-only service and whose changelogs are
harder to review than SQL; and over Spring Data JDBC, whose aggregate model
fights the entity-per-table, lazy-in-domain design above).

- **Dependencies:** `spring-boot-starter-data-jpa`, the Postgres driver,
  `flyway-core` + `flyway-database-postgresql`, and test-scoped
  `spring-boot-testcontainers` + Testcontainers Postgres.
- **Schema ownership:** Flyway migrations — hand-written Postgres SQL in
  `src/main/resources/db/migration/V<n>__<slug>.sql` — are the only thing that
  creates or alters schema. Hibernate runs `ddl-auto: validate`, so
  entity/schema drift fails startup. No production migration ships in this
  sub-project; V1 arrives with spec 2's first table.
- **`open-in-view: false`** from day one — the default lets lazy loading leak
  into the web layer, quietly violating the entities-stay-in-their-domain rule.
- **Column conventions** (documented now, first applied in spec 2): every table
  has `id uuid primary key`, `created_at timestamptz(6) not null`,
  `updated_at timestamptz(6) not null` — microsecond precision, timezone-aware,
  storing UTC instants. Cross-domain links are plain FK columns with real
  constraints.
- **Base-class contract** (class lands in spec 2 with its first consumer):
  a `@MappedSuperclass` in `common.persistence` giving every entity a UUID v4
  id application-generated via Hibernate's `@UuidGenerator` (identifiable
  pre-flush, no DB round-trip), and `Instant` `createdAt`/`updatedAt` managed
  by `@CreationTimestamp`/`@UpdateTimestamp`. Hibernate manages the timestamps
  rather than DB `DEFAULT now()` so values exist on the Java object immediately
  after save and there is exactly one mechanism.

## Configuration and environments

- `application.yml` composes the datasource from five env vars with local-dev
  defaults: `DB_HOST` (`localhost`), `DB_PORT` (`5432`), `DB_NAME`, `DB_USER`,
  `DB_PASSWORD` → `jdbc:postgresql://…`. Individual parts, not one URL, because
  Render blueprints can inject a database's host/port/database/user/password
  into env vars but cannot template them into a JDBC URL (Render's own
  `connectionString` is `postgres://`-scheme, which JDBC rejects).
- **Plug-and-play principle** (applies to all future infrastructure):
  - *Databases* swap at the connection level — the `DB_*` vars accept any
    Postgres provider (Render, Neon, RDS, …) with zero code change. The SQL
    dialect is deliberately **not** abstracted: migrations stay
    Postgres-flavored; a non-Postgres engine would be a migration project and
    an abstraction pretending otherwise costs more than it saves.
  - *Everything else* (cache, email, future integrations) is consumed through
    an interface this project owns with the vendor implementation injected —
    a provider swap is a new adapter plus config, never a consumer rewrite.
- **Local dev:** docker compose gains a pinned `postgres` service (same major
  version Render runs) with a named volume, a healthcheck, and throwaway dev
  credentials; the app service waits on `service_healthy`.
  `docker compose up --build` stays the one-command local stack, and
  `./mvnw spring-boot:run` works against it with zero configuration.
- **Credentials:** compose interpolates `${VAR:-default}`-style variables —
  harmless localhost-only defaults committed, a gitignored `.env` overrides
  them, a committed `.env.example` documents the knobs. Production credentials
  exist only as Render-injected env vars (consistent with the Gitleaks gate).
- **Production:** `render.yaml` gains a free-plan `databases:` entry and maps
  the five `DB_*` env vars `fromDatabase`.

## The free-tier trade-off (accepted)

Render's free Postgres expires 30 days after creation, holds 1 GB, has no
backups, and deletes its data 14 days after expiry. **Accepted knowingly:**
production is effectively a wipeable demo database until the project warrants
paying. The reference doc carries the runbook note: recreate the database,
redeploy, Flyway rebuilds the schema from migrations — data is gone, structure
is not. Nothing in code knows which tier the database is; upgrading (or moving
to any other Postgres) is purely a config change.

Standing cost policy: the project runs on free tiers. Any future feature that
would cost money gets an explicit now-vs-postpone discussion before it enters a
design.

## Testing

One test replaces the deleted hello test: a **boot smoke test** —
`@SpringBootTest` against Testcontainers Postgres via `@ServiceConnection`,
proving the app starts, Flyway runs (zero migrations yet), and
`ddl-auto: validate` passes. Every later sub-project inherits this guardrail:
a broken migration or entity/schema drift fails this test.

Deliberately absent: no fake tables and no test-scoped migrations. The
`BaseEntity` convention test (UUID assigned on save, timestamps populated at
microsecond precision, `updated_at` moves on update while `created_at` does
not) waits for spec 2, when the first real table exists to assert against.
Consequently `BaseEntity` itself also lands in spec 2 — landing it here would
add dead, untested code and trip the coverage gate.

Coverage: main code after this sub-project is only `ZarlaniaApiApplication`,
covered by the smoke test (standard entry-point exclusion if `main()` trips
the 80% JaCoCo gate — decided at implementation).

CI: no workflow changes expected. GitHub's Ubuntu runners have Docker, so
`./mvnw verify` runs Testcontainers as-is. Locally the only new requirement is
a running Docker daemon, which compose-based dev already assumes.

## Error handling

Deliberately boring: misconfiguration fails fast at startup — missing database
→ connection error, schema drift → validate failure, broken migration → Flyway
failure. No runtime error paths are introduced because no endpoints exist after
`hello` is deleted.

## Decisions log

| Decision | Choice | Alternatives rejected |
| -------- | ------ | --------------------- |
| Migration tool | Flyway, plain SQL | Liquibase (unneeded abstraction, less reviewable) |
| ORM | Spring Data JPA / Hibernate | Spring Data JDBC (no lazy in-domain relations) |
| Test database | Testcontainers Postgres | H2 compat mode (dialect drift risk); both-by-layer (two configs) |
| Production database | Render free Postgres, 30-day wipe accepted | External free Postgres (Neon); paid Render |
| Redis / cache | Deferred to first consumer | Provision now; drop entirely |
| `hello` feature | Deleted | Restructure as exemplar; leave until spec 2 |
| Timestamps | `timestamptz(6)`, Hibernate-managed | DB `DEFAULT now()`; zoneless `timestamp(6)` |
| `BaseEntity` timing | Spec 2, with first consumer | Land now (dead code, coverage trip) |
