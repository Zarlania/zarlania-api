---
id: '000001'
title: Persistence foundation
description: How the Postgres datasource, Flyway migrations, and JPA are configured
  and operated.
tags:
- configuration
- persistence
created: '2026-07-26'
updated: '2026-07-26'
related: []
---

# Persistence foundation

<!-- reference-table:start -->
| Field | Value |
| ----- | ----- |
| ID | 000001 |
| Title | Persistence foundation |
| Description | How the Postgres datasource, Flyway migrations, and JPA are configured and operated. |
| Tags | configuration, persistence |
| Created | 2026-07-26 |
| Updated | 2026-07-26 |
| Related | — |
<!-- reference-table:end -->

The service persists to Postgres through Spring Data JPA, with Flyway owning the
schema. Configuration lives in `src/main/resources/application.yml`; the local
and hosted environments each supply their own values through environment
variables.

## Datasource configuration

The JDBC URL is composed from five environment variables rather than supplied
as a single connection string:

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `DB_HOST` | `localhost` | Database host. |
| `DB_PORT` | `5432` | Database port. |
| `DB_NAME` | `zarlania` | Database name. |
| `DB_USER` | `zarlania` | Connection username. |
| `DB_PASSWORD` | `zarlania` | Connection password. |

`application.yml` assembles these into
`jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`, with `DB_USER` and
`DB_PASSWORD` supplied separately as `spring.datasource.username` and
`spring.datasource.password`. The defaults match `docker-compose.yml`'s
`postgres` service, so `docker compose up postgres` followed by
`./mvnw spring-boot:run` needs no `.env` file. Note that a `.env` file only
changes what `docker compose` injects; `spring-boot:run` always falls back to
the defaults above, whatever `.env` says.

The parts are composed rather than supplied as a single URL for two reasons:

- **A Render blueprint cannot template a URL.** `render.yaml` injects each part
  on its own, with `fromDatabase` reading the managed database's `host`, `port`,
  `database`, `user` and `password` properties. Render also exposes a
  `connectionString` property, but that is a `postgres://` URL and the
  PostgreSQL JDBC driver only accepts URLs starting with `jdbc:postgresql:`, so
  it cannot be handed to `spring.datasource.url` as-is — and a blueprint has no
  string manipulation to rewrite it. The same reasoning is recorded as a comment
  in `application.yml`.
- **Any Postgres provider plugs in.** Because the five variables are the only
  contract, swapping Render's managed database for another host, or for a
  self-hosted instance, requires no code or config-shape change — only
  different values for the same five variables.

## Schema ownership and conventions

Flyway is the sole owner of the schema. Migrations live in
`src/main/resources/db/migration` — Flyway's default location — and run
automatically on startup, ahead of Hibernate. The ordering comes from Spring
Boot's database-initialization wiring: Flyway's migration bean is detected as a
database initializer and any `EntityManagerFactory` bean is made to depend on
it, so the migrations finish before Hibernate validates its mappings. This is
bean ordering inside the application context, not a phase that runs before the
context starts.

The migration directory currently holds only a `.gitkeep`. There is no domain
model yet, so everything below describes what migrations and entities must do,
not what they already do.

Hibernate's `ddl-auto` is set to `validate`: it never creates or alters
tables, it only checks that the JPA entity mappings match the schema Flyway
already built. A mismatch fails startup immediately instead of letting the
two silently drift apart.

`spring.jpa.open-in-view` is set to `false`. The Spring Boot default (`true`)
keeps the Hibernate session open through the web layer, which lets a
controller trigger a lazy load outside its originating service call — a
convenience that hides N+1 queries and lets persistence concerns leak across
the controller/service boundary this codebase otherwise keeps separate.
With it off, the session lasts only as long as the surrounding transaction. A
service method that returns data touching lazy associations therefore has to
carry its own `@Transactional` boundary and resolve those associations inside
it; without a transaction, each repository call gets its own short-lived
session and anything lazy touched after that call returns will fail.

Migration files are named `V<n>__<slug>.sql`, following Flyway's default
naming convention (for example, `V1__create_users_table.sql`). `<n>` is a
strictly increasing version number and `<slug>` is a short, lower-snake-case
description of what the migration does.

Every table follows the same column shape (`CLAUDE.md` is the canonical
statement of this convention):

- `id` — `uuid`, primary key.
- `created_at` and `updated_at` — `timestamptz(6) not null`: timezone-aware,
  microsecond precision.
- Cross-table references are declared as real foreign-key constraints, not bare
  id columns.
- Case-insensitive unique text columns (an email or username, say) use `citext`
  rather than `text` with a functional index, so uniqueness and equality are
  case-insensitive at the column level. `citext` is a Postgres extension, so a
  migration has to create it before the first column uses it.

## Free-tier runbook

The hosted database is the `zarlania-db` entry in `render.yaml`'s `databases:`
block, on Render's free plan. Treat it as disposable, non-production
infrastructure. As recorded in `render.yaml`'s comment, the instance expires 30
days after creation and Render deletes its data 14 days later; upgrading to a
paid plan is a Render-side change, so escaping that clock does not touch this
repository. Those windows are Render's, not something this repository can
enforce or verify — confirm them, and what an upgrade does to existing data,
against Render's current documentation before depending on them.

When the data is gone, the runbook is:

1. Recreate the database in Render.
2. Redeploy the API against the new instance.
3. Flyway rebuilds the schema from the migrations in
   `src/main/resources/db/migration` on the first boot against the fresh
   database.

Only the data is lost. The schema survives in the checked-in migrations, which
rebuild it against any empty database.

Adding the `databases:` block to `render.yaml` does not provision anything by
itself on a Blueprint instance that already exists — Render picks up a new
database only when the blueprint is synced, which has to be triggered on
Render's side. Do that before (or as part of) the deploy carrying this change,
not after: the service needs a reachable datasource to start, `render.yaml`
gates deploys on `healthCheckPath: /actuator/health`, and that endpoint reports
the datasource's state — actuator auto-configures a datasource health indicator
because `spring-boot-starter-data-jpa` puts a `DataSource` in the context, and
`management.endpoints.web.exposure.include` exposes `health`.

## Testing

`src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java` boots the
full Spring context against a real `postgres:17-alpine` container via
Testcontainers — the same major version `docker-compose.yml` and `render.yaml`
pin. One test queries the database to prove the context started; the other
checks that `flyway_schema_history` exists, proving Flyway ran. As migrations
and entities land, this is the guardrail against broken migrations and
entity/schema drift: `ddl-auto: validate` only compares entities to whatever
schema exists, so a migration that does not apply cleanly, or an entity that no
longer matches the migrated schema, fails here instead of first surfacing in a
deployed environment.

Because this test starts a real Postgres container, Docker must be running
locally for `./mvnw verify` (and for `./mvnw test`) to pass.
