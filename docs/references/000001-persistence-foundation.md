---
id: '000001'
title: Persistence foundation
description: How the Postgres datasource, Flyway migrations, and JPA are configured
  and operated.
tags:
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
| Tags | persistence |
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
`postgres` service, so running `docker compose up postgres` and then
`./mvnw spring-boot:run` needs no `.env` file.

The parts are composed rather than supplied as a single URL for two reasons:

- **Render blueprints cannot template a URL.** `render.yaml` wires each part
  from the managed database's `fromDatabase` properties (`host`, `port`,
  `database`, `user`, `password`). Render's own `connectionString` output uses
  the `postgres://` scheme, which the JDBC driver rejects outright — there is
  no way to turn that single string into a valid JDBC URL at the blueprint
  layer.
- **Any Postgres provider plugs in.** Because the five variables are the only
  contract, swapping Render's managed database for another host, or for a
  self-hosted instance, requires no code or config-shape change — only
  different values for the same five variables.

## Schema ownership and conventions

Flyway is the sole owner of the schema. Migrations live in
`src/main/resources/db/migration` and run automatically on application
startup, before the JPA `EntityManagerFactory` is created — Spring Boot wires
Flyway to run ahead of it via `@DependsOn`, not before the whole application
context initializes.

Hibernate's `ddl-auto` is set to `validate`: it never creates or alters
tables, it only checks that the JPA entity mappings match the schema Flyway
already built. A mismatch fails startup immediately instead of letting the
two silently drift apart.

`spring.jpa.open-in-view` is set to `false`. The Spring Boot default (`true`)
keeps the Hibernate session open through the web layer, which lets a
controller trigger a lazy load outside its originating service call — a
convenience that hides N+1 queries and lets persistence concerns leak across
the controller/service boundary this codebase otherwise keeps separate.
Disabling it means all lazy associations must be resolved inside the service
layer, where the session is still open.

Migration files are named `V<n>__<slug>.sql`, following Flyway's default
naming convention (for example, `V1__create_users_table.sql`). `<n>` is a
strictly increasing version number and `<slug>` is a short, lower-snake-case
description of what the migration does.

Tables created by migrations follow a standard column shape:

- `id` — `uuid`, primary key.
- `created_at` / `updated_at` — `timestamptz(6) not null`, so every row
  carries sub-second, timezone-aware timestamps.
- Case-insensitive unique columns (such as an email or username) use the
  `citext` type rather than `text` with a functional index, so uniqueness and
  equality checks are case-insensitive at the column level.

## Free-tier runbook

The hosted database (`render.yaml`, service `zarlania-db`) runs on Render's
free plan, which is time-limited rather than always-on: the instance expires
30 days after creation, and its data is deleted 14 days after that if it is
not recreated. When it expires, the runbook is:

1. Recreate the database in Render.
2. Redeploy the API against the new instance.
3. Flyway rebuilds the schema from the migrations in
   `src/main/resources/db/migration` on the first boot against the fresh
   database.

The data held in the expired database is gone; the schema is not, since it is
fully described by the checked-in migrations and reconstructed from them
every time.

Adding the `databases:` block to `render.yaml` does not itself provision
anything on a Render Blueprint instance that already exists — Render only
picks up a new database after a manual blueprint sync. Also, because
`spring-boot-starter-data-jpa` adds a datasource health indicator, the first
deploy after that change merges will fail `/actuator/health` until the
database is actually attached, so the sync should happen before (or as part
of) that deploy, not after.

## Testing

`src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java` boots the
full Spring context against a real `postgres:17-alpine` container via
Testcontainers — the same major version pinned in `docker-compose.yml` and
`render.yaml`. It asserts that the application starts successfully and that
Flyway has run (by checking for the `flyway_schema_history` table). This is
the guardrail against broken migrations and entity/schema drift: because
`ddl-auto: validate` only compares entities to whatever schema already
exists, a migration that does not apply cleanly, or an entity that no longer
matches the migrated schema, fails this test instead of first surfacing at
runtime in a deployed environment.

Because this test starts a real Postgres container, Docker must be running
locally for `./mvnw verify` (and for `./mvnw test`) to pass.
