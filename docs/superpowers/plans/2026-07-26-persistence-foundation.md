# Persistence Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-25-persistence-foundation-design.md`

**Goal:** Wire the backend to Postgres (compose locally, Render in prod, Testcontainers in tests) with Flyway owning schema, and replace the flat feature-package convention with domain packages + layer sub-packages.

**Architecture:** Flyway (plain SQL) is the only thing that creates or alters schema; Hibernate runs `ddl-auto: validate`. The datasource is composed from five `DB_*` env vars with localhost defaults. No production migration and no `BaseEntity` ship in this sub-project — the boot smoke test against Testcontainers is the deliverable guardrail. The `hello` scaffolding is deleted.

**Tech Stack:** Spring Boot 4.1 (Java 25), Spring Data JPA, Flyway, PostgreSQL 17, Testcontainers, Maven via `./mvnw`.

## Global Constraints

- Always `./mvnw`, never system `mvn`. Docker must be running (Testcontainers + compose).
- Run `./mvnw spotless:apply` before every commit; `./mvnw verify` is the full gate (Spotless, Checkstyle, SpotBugs, JaCoCo 80% line+branch).
- Do not bump the POM version (`0.0.1-SNAPSHOT` is frozen).
- Checkstyle ceilings: methods < 40 lines, files < 400, complexity < 10, nesting < 2, no magic numbers (−1, 0, 1, 2 are allowed), no field injection. Test sources are checked too.
- Postgres major version is **17** and must match in `docker-compose.yml`, `render.yaml`, and the test container image.
- Env var names are exactly: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
- Commit messages: `#<ISSUE> <type>: <description>` where `<ISSUE>` is the tracking-issue number created in Task 0. End every commit body with the `Co-Authored-By: Claude …` trailer if the committer is an agent that uses one.
- YAML must pass `yamllint --strict -c .yamllint.yml .`; Markdown must pass `npx markdownlint-cli2` (both run in the Lint CI workflow).
- `docs/superpowers/**` is excluded from linters — plan/spec edits never break lint.

---

### Task 0: Tracking issue and branch

Every change needs an issue (repo rule). The spec work lives on `26-auth-design-specs`; the implementation gets its own issue and branch.

- [ ] **Step 1: Create the issue**

```bash
gh issue create \
  --title "chore: implement the persistence foundation and domain package convention" \
  --label chore \
  --body "$(cat <<'EOF'
### Kind of work

Build or tooling

### What needs doing?

Implement docs/superpowers/specs/2026-07-25-persistence-foundation-design.md: Postgres via docker compose locally and Render in production (DB_* env vars), Flyway with ddl-auto: validate, Testcontainers boot smoke test, deletion of the hello feature, the domain + layer-sub-package convention in CLAUDE.md, and a persistence reference doc.

### Why now?

Spec 1 of the account-creation/login/authentication chain; every later spec builds on this foundation.

### Scope and non-goals

No domain code, no production migration, no BaseEntity (spec 2), no Redis. Follows the spec exactly.

### Before submitting

- [x] I searched existing issues and this is not a duplicate.
- [x] This change does not alter user-facing behaviour (otherwise file a bug or feature request).
EOF
)"
```

Expected: outputs the new issue URL. Record its number; it is `<ISSUE>` in every later commit message.

- [ ] **Step 2: Create the branch from master**

```bash
git fetch origin master
git checkout -b <ISSUE>-persistence-foundation origin/master
```

Expected: `Switched to a new branch '<ISSUE>-persistence-foundation'`.

---

### Task 1: Delete the hello feature

**Files:**

- Delete: `src/main/java/com/zarlania/api/hello/HelloController.java`
- Delete: `src/test/java/com/zarlania/api/hello/HelloControllerTest.java`

**Interfaces:**

- Consumes: nothing.
- Produces: a main source tree containing only `ZarlaniaApiApplication` (which JaCoCo already excludes in `pom.xml`, so the coverage gate stays green with zero tests).

- [ ] **Step 1: Delete the two files (the only contents of their packages)**

```bash
git rm src/main/java/com/zarlania/api/hello/HelloController.java \
       src/test/java/com/zarlania/api/hello/HelloControllerTest.java
```

Expected: both files staged as deleted; the empty `hello/` directories disappear with them.

- [ ] **Step 2: Verify the build is still green with no tests**

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`. Surefire reports `Tests run: 0`; the JaCoCo check passes because the only remaining class is excluded (an empty bundle has no counters to fail).

- [ ] **Step 3: Commit**

```bash
git commit -m "#<ISSUE> chore: delete the hello scaffolding feature"
```

---

### Task 2: Persistence dependencies

**Files:**

- Modify: `pom.xml` (dependencies block, lines 59–85)

**Interfaces:**

- Consumes: nothing.
- Produces: classpath for Task 3 — `spring-boot-starter-data-jpa`, the Postgres driver, `flyway-core` + `flyway-database-postgresql`, and test-scoped `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`. All versions come from the Spring Boot parent BOM — declare none.

- [ ] **Step 1: Add the main dependencies**

In `pom.xml`, directly after the `spring-boot-starter-webmvc` dependency and before the Lombok one, insert:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
```

- [ ] **Step 2: Add the test dependencies**

Directly after the `spring-boot-starter-webmvc-test` dependency, insert:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 3: Verify resolution and the enforcer's convergence rule**

```bash
./mvnw -q validate compile
```

Expected: `BUILD SUCCESS`. If the enforcer's `dependencyConvergence` rule fails on a transitive of Testcontainers, resolve it by adding the conflicting artifact to the pom's `<dependencyManagement>` pinned to the *newer* of the converging versions — do not weaken the enforcer rule.

- [ ] **Step 4: Commit**

```bash
./mvnw spotless:apply
git add pom.xml
git commit -m "#<ISSUE> build: add JPA, Flyway, Postgres, and Testcontainers dependencies"
```

---

### Task 3: Datasource configuration, driven by the boot smoke test

**Files:**

- Create: `src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/.gitkeep`

**Interfaces:**

- Consumes: Task 2's dependencies.
- Produces: the running configuration every later spec inherits — `DB_*` env composition, `ddl-auto: validate`, `open-in-view: false`, Flyway enabled with `db/migration` as its (currently empty) location; and the smoke test later specs extend.

- [ ] **Step 1: Write the failing boot smoke test**

Create `src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java`:

```java
package com.zarlania.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ZarlaniaApiApplicationTest {

  // Same major version render.yaml and docker-compose.yml pin for production and local dev.
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void applicationBootsAgainstPostgres() {
    Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    assertThat(result).isEqualTo(1);
  }

  @Test
  void flywayCreatesItsSchemaHistoryTable() {
    Integer tables =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name = 'flyway_schema_history'",
            Integer.class);
    assertThat(tables).isEqualTo(1);
  }
}
```

- [ ] **Step 2: Run it to verify it fails for the right reason**

```bash
./mvnw test -Dtest=ZarlaniaApiApplicationTest
```

Expected: FAIL. Flyway aborts startup because its configured location `classpath:db/migration` does not exist yet (`IllegalStateException` … "Cannot find migrations location"), or the context fails on datasource configuration. Either failure proves the test exercises real wiring.

- [ ] **Step 3: Configure the datasource, JPA, and Flyway**

In `src/main/resources/application.yml`, replace the top `spring:` block:

```yaml
spring:
  application:
    name: zarlania-api
  # Composed from parts, not one URL: Render blueprints can inject a database's
  # host/port/database/user/password into env vars but cannot template them into
  # a JDBC URL (Render's own connectionString is postgres://, which JDBC rejects).
  # Any Postgres provider plugs in through these five variables.
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:zarlania}
    username: ${DB_USER:zarlania}
    password: ${DB_PASSWORD:zarlania}
  jpa:
    hibernate:
      # Flyway owns the schema. Hibernate only checks that entities match it,
      # so drift fails startup instead of silently diverging.
      ddl-auto: validate
    # The default (true) holds the session open into the web layer, letting lazy
    # loads leak entities across domain boundaries.
    open-in-view: false
```

Everything below the `spring:` block (`server:`, `management:`, `zarlania:`) stays untouched. Flyway needs no explicit YAML: the starter enables it against `classpath:db/migration` by default.

- [ ] **Step 4: Create the migration location**

```bash
mkdir -p src/main/resources/db/migration
touch src/main/resources/db/migration/.gitkeep
```

(Flyway only reads `V*.sql`/`R*.sql`; the `.gitkeep` just makes the location exist. The first real migration arrives with spec 2.)

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./mvnw test -Dtest=ZarlaniaApiApplicationTest
```

Expected: PASS, 2 tests. First run downloads the `postgres:17-alpine` image.

- [ ] **Step 6: Full gate, then commit**

```bash
./mvnw spotless:apply && ./mvnw verify
git add src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java \
        src/main/resources/application.yml src/main/resources/db/migration/.gitkeep
git commit -m "#<ISSUE> feat: wire Postgres, Flyway, and the Testcontainers boot smoke test"
```

Expected: `BUILD SUCCESS`; JaCoCo passes (the excluded application class is all that exists in main).

---

### Task 4: Local Postgres via docker compose

**Files:**

- Modify: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**

- Consumes: Task 3's `DB_*` variable names and localhost defaults.
- Produces: `docker compose up --build` as the one-command local stack; `./mvnw spring-boot:run` working against the published port with zero configuration.

- [ ] **Step 1: Replace `docker-compose.yml` with the two-service stack**

```yaml
# Local development only. Render builds its own image from the Dockerfile and
# does not read this file.
#
#   docker compose up --build      start the API on http://localhost:8080
#   docker compose up postgres     start only the database (for ./mvnw spring-boot:run)
#   docker compose down            stop and remove containers
#
# Values are overridable via a gitignored .env file; see .env.example.

services:
  api:
    build:
      context: .
      dockerfile: Dockerfile
      args:
        APP_VERSION: ${APP_VERSION:-0.0.0-dev}
    image: zarlania-api:local
    container_name: zarlania-api
    ports:
      - "${API_PORT:-8080}:8080"
    environment:
      PORT: 8080
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-local}
      JAVA_OPTS: ${JAVA_OPTS:--XX:MaxRAMPercentage=75}
      # In-network address; the host-published port below is for spring-boot:run.
      DB_HOST: postgres
      DB_PORT: "5432"
      DB_NAME: ${DB_NAME:-zarlania}
      DB_USER: ${DB_USER:-zarlania}
      DB_PASSWORD: ${DB_PASSWORD:-zarlania}
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 40s
    restart: unless-stopped

  postgres:
    # Same major version render.yaml pins for production and the smoke test uses.
    image: postgres:17-alpine
    container_name: zarlania-postgres
    ports:
      - "${DB_PORT:-5432}:5432"
    environment:
      POSTGRES_DB: ${DB_NAME:-zarlania}
      POSTGRES_USER: ${DB_USER:-zarlania}
      # Harmless dev-only default; the container only ever binds to localhost.
      POSTGRES_PASSWORD: ${DB_PASSWORD:-zarlania}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  postgres-data:
```

- [ ] **Step 2: Create `.env.example`**

```bash
cat > .env.example <<'EOF'
# Local development overrides, read by docker compose (and documented for
# ./mvnw spring-boot:run, whose defaults match). Copy to .env and edit;
# .env is gitignored. Nothing in this file is a production secret.

# Host port the API publishes on.
API_PORT=8080

# Host port Postgres publishes on.
DB_PORT=5432

# Local database name and credentials. Harmless localhost-only defaults.
DB_NAME=zarlania
DB_USER=zarlania
DB_PASSWORD=zarlania
EOF
```

(`.gitignore` already contains `.env`, `.env.*`, and `!.env.example` — no change needed there.)

- [ ] **Step 3: Verify the stack and the lint gate**

```bash
yamllint --strict -c .yamllint.yml docker-compose.yml
docker compose up --build --detach
docker compose ps
curl -fsS http://localhost:8080/actuator/health
docker compose down
```

Expected: yamllint silent; both containers reach `healthy`; health endpoint returns `{"status":"UP"...}`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml .env.example
git commit -m "#<ISSUE> chore: run Postgres in the local compose stack"
```

---

### Task 5: Render database wiring

**Files:**

- Modify: `render.yaml`

**Interfaces:**

- Consumes: Task 3's `DB_*` names.
- Produces: production database provisioning. Free-tier caveat (spec-accepted): the instance expires after 30 days and deletes its data 14 days later; recreate + redeploy rebuilds schema from migrations.

- [ ] **Step 1: Add the database and its env wiring**

In `render.yaml`, append to the `envVars:` list of the `zarlania-api` service:

```yaml
      # Composed into the JDBC URL by application.yml; blueprints cannot
      # template a URL string, so the parts are injected individually.
      - key: DB_HOST
        fromDatabase:
          name: zarlania-db
          property: host
      - key: DB_PORT
        fromDatabase:
          name: zarlania-db
          property: port
      - key: DB_NAME
        fromDatabase:
          name: zarlania-db
          property: database
      - key: DB_USER
        fromDatabase:
          name: zarlania-db
          property: user
      - key: DB_PASSWORD
        fromDatabase:
          name: zarlania-db
          property: password
```

Then add a top-level `databases:` block at the end of the file:

```yaml
# Free-tier reality (accepted in the spec): this instance expires 30 days after
# creation and its data is deleted 14 days later. Recreate the database and
# redeploy; Flyway rebuilds the schema from migrations. Data is gone, structure
# is not. Upgrading the plan later is a Render-side change only.
databases:
  - name: zarlania-db
    plan: free
    region: oregon
    databaseName: zarlania
    user: zarlania
    postgresMajorVersion: "17"
```

- [ ] **Step 2: Lint**

```bash
yamllint --strict -c .yamllint.yml render.yaml
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add render.yaml
git commit -m "#<ISSUE> chore: provision the free Render Postgres in the blueprint"
```

---

### Task 6: CLAUDE.md — the domain package convention

**Files:**

- Modify: `CLAUDE.md`

**Interfaces:**

- Consumes: the spec's convention rules.
- Produces: the instructions every future agent session reads.

- [ ] **Step 1: Update the Stack table**

Add three rows after the `Framework` row:

```markdown
| Persistence | Spring Data JPA (Hibernate), `ddl-auto: validate` |
| Database   | PostgreSQL 17 — compose locally, Render in production, Testcontainers in tests |
| Migrations | Flyway, plain SQL in `src/main/resources/db/migration` |
```

- [ ] **Step 2: Replace the Layout section's tree and its closing paragraph**

The tree becomes:

```text
src/main/java/com/zarlania/api/
  ZarlaniaApiApplication.java   Entry point
  common/                       Domain-agnostic infrastructure only (e.g. persistence
                                base classes). Nothing with business meaning.
  <domain>/                     One package per domain, layer sub-packages inside:
    controllers/                HTTP endpoints
    services/                   Business rules
    repositories/               Spring Data interfaces
    entities/                   JPA entities — never leave the domain
    dtos/                       Records crossing the domain boundary
src/main/resources/
  application.yml               Configuration, with env-var overrides
  db/migration/                 Flyway migrations (V<n>__<slug>.sql) — the only thing
                                that creates or alters schema
src/test/java/com/zarlania/api/  Tests, mirroring the main package structure
```

The paragraph after it ("Code is organised by feature, not by layer…") is replaced with:

```markdown
Code is organised by **domain**. Each domain is a top-level package holding only
the layer sub-packages it needs. Rules that keep domains separable (so a domain
can be lifted out of the monolith with minimal work):

- **Entities never leave their domain.** Cross-domain references are plain
  foreign-key id columns plus DTO lookups through the owning domain's service —
  never a mapped JPA relation across domains. Within one domain, JPA relations
  (including lazy loading) are fine.
- **Aggregating (parent) domains are allowed.** A parent may group sub-domains
  (`fruit/controllers`, `fruit/apple/controllers`, …). Every sub-domain is a
  full domain boundary in its own right; the parent level holds orchestration
  only — never entities.
- **Every table** gets `id uuid primary key`, `created_at timestamptz(6) not
  null`, `updated_at timestamptz(6) not null`, with real FK constraints.
  Case-insensitive unique text columns use `citext`.
- Do not create top-level `controllers/`, `services/` or `models/` packages for
  the whole application.
```

- [ ] **Step 3: Extend the Commands table and conventions**

Add one row to the Commands table:

```markdown
| `docker compose up postgres` | Just the local database, for `spring-boot:run`. |
```

Add to the Conventions bullet list:

```markdown
- **Flyway owns the schema.** Never enable Hibernate DDL generation; write a
  versioned migration instead. Tests run against real Postgres via
  Testcontainers, so Docker must be running for `./mvnw verify`.
- Datasource config comes from `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/
  `DB_PASSWORD`, defaulting to the local compose stack. Any Postgres provider
  plugs in through those five variables — never hardcode a JDBC URL.
```

- [ ] **Step 4: Lint and commit**

```bash
npx markdownlint-cli2 CLAUDE.md
git add CLAUDE.md
git commit -m "#<ISSUE> docs: adopt the domain package convention in CLAUDE.md"
```

Expected: markdownlint silent.

---

### Task 7: Persistence reference doc

**Files:**

- Create: `docs/references/0000NN-persistence-foundation.md` (id assigned by the tooling)
- Modify (generated): `docs/references/README.md`, possibly `docs/references/_tags.md`

**Interfaces:**

- Consumes: the implemented configuration from Tasks 3–5.
- Produces: the living doc future sessions read before touching persistence.

- [ ] **Step 1: Invoke the `creating-reference-docs` skill**

Use the Skill tool: `creating-reference-docs`. Follow it exactly (it scaffolds via `references_cli.py create`, then finalizes by dispatching the `technical-writer` agent). Do not hand-write frontmatter.

- [ ] **Step 2: Doc content requirements**

Title: **Persistence foundation**. Check existing tags with `python3 docs/tooling/references_cli.py meta`; if no persistence-appropriate tag exists, add `persistence` (one line, alphabetical position) to `docs/references/_tags.md` as the skill directs. The body must cover, in this order:

1. The five `DB_*` env vars, their defaults, and why the JDBC URL is composed from parts (Render blueprints cannot template URLs; any Postgres provider plugs in).
2. Flyway as sole schema owner; `ddl-auto: validate`; `open-in-view: false` and why; migration naming `V<n>__<slug>.sql`; the standard columns (`id uuid pk`, `created_at`/`updated_at` `timestamptz(6) not null`, `citext` for case-insensitive uniques).
3. The free-tier runbook note, verbatim in spirit: the Render database expires every 30 days and deletes data 14 days later — recreate the database in Render, redeploy, Flyway rebuilds schema from migrations; data is gone, structure is not.
4. Testing: the Testcontainers boot smoke test (`ZarlaniaApiApplicationTest`) is the guardrail that catches broken migrations and entity/schema drift; Docker is required for `./mvnw verify`.

- [ ] **Step 3: Validate and commit**

```bash
python3 docs/tooling/references_cli.py validate
npx markdownlint-cli2 "docs/references/**/*.md"
git add docs/references/
git commit -m "#<ISSUE> docs: add the persistence foundation reference doc"
```

Expected: validate exits 0; markdownlint silent.

---

### Task 8: Full verification and PR

**Files:** none new.

- [ ] **Step 1: Run every gate exactly as CI does**

```bash
./mvnw verify
yamllint --strict -c .yamllint.yml .
npx markdownlint-cli2
python3 docs/tooling/references_cli.py validate
```

Expected: all pass. `verify` runs the 2 smoke tests against Testcontainers.

- [ ] **Step 2: Push and open the PR**

```bash
git push -u origin <ISSUE>-persistence-foundation
gh pr create \
  --title "#<ISSUE> chore: implement the persistence foundation" \
  --label patch \
  --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-07-25-persistence-foundation-design.md:
Postgres via compose/Render/Testcontainers on the five DB_* variables, Flyway
as sole schema owner with ddl-auto: validate, the boot smoke test, deletion of
the hello feature, the domain package convention in CLAUDE.md, and the
persistence reference doc.

Closes #<ISSUE>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR opens; PR Lint passes (title/branch/body all reference `<ISSUE>`; `patch` release label present — this changes no user-facing behaviour).

---

## Self-Review (completed at authoring)

- **Spec coverage:** deps/config (T2–T3), compose + `.env.example` (T4), Render + runbook (T5), convention + CLAUDE.md (T6), reference doc (T7), hello deletion (T1), smoke test (T3), coverage handling (already-present JaCoCo exclusion, noted in T1). The `BaseEntity` contract is documentation-only in this sub-project (T6/T7 text) per the spec.
- **Placeholders:** `<ISSUE>` is defined operationally in Task 0 Step 1; no other symbolic values.
- **Type consistency:** the only produced code symbol is `ZarlaniaApiApplicationTest` with its two test methods; env var names match across T3/T4/T5/T6.
