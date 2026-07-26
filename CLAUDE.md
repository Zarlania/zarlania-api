# CLAUDE.md

Guidance for AI coding agents working in this repository. This file is the
canonical set of agent instructions; `AGENTS.md` points here.

## What this is

`zarlania-api` is the open-source backend for Zarlania — a Spring Boot service
written in Java. The browser client lives in a separate repository,
[Zarlania/zarlania-app](https://github.com/Zarlania/zarlania-app).

> **Status: persistence foundation.** Postgres, Flyway, and JPA are wired
> (see Stack and Layout below), but there is still no domain model, no domain
> code, and no authentication yet.
>
> **PLACEHOLDER — expand as the project takes shape:** domain concepts and
> vocabulary, module boundaries, authentication and authorization model,
> external integrations, and the API versioning policy.

## Stack

| Concern    | Choice                                     |
| ---------- | ------------------------------------------ |
| Language   | Java 25 (Temurin)                          |
| Framework  | Spring Boot 4.1                            |
| Persistence | Spring Data JPA (Hibernate), `ddl-auto: validate` |
| Database   | PostgreSQL 17 — compose locally, Render in production, Testcontainers in tests |
| Migrations | Flyway, plain SQL in `src/main/resources/db/migration` |
| Build      | Maven, via the committed `./mvnw` wrapper  |
| Formatting | Spotless with Google Java Style            |
| Static analysis | Checkstyle (design rules), SpotBugs with FindSecBugs |
| Boilerplate | Lombok, restricted by `lombok.config`     |
| Linting    | yamllint and markdownlint, in CI           |
| Testing    | JUnit 5, Spring Boot test slices, and Testcontainers |
| Container  | Docker, with Compose for local development |
| Hosting    | Render, configured in `render.yaml`        |

## Commands

| Command                  | Purpose                                                  |
| ------------------------ | -------------------------------------------------------- |
| `./mvnw verify`          | Compile, test, and run every quality gate. **This is what CI runs.** |
| `./mvnw test`            | Tests only.                                              |
| `./mvnw spotless:apply`  | Reformat. Run this before committing.                    |
| `./mvnw checkstyle:check` | Design and complexity rules only.                       |
| `./mvnw spotbugs:check`  | Bug and security analysis only. Needs a `compile` first. |
| `./mvnw spring-boot:run` | Run locally on port 8080.                                |
| `docker compose up --build` | Run in a container.                                   |
| `docker compose up postgres` | Just the local database, for `spring-boot:run`. |

Always use `./mvnw`, never a system `mvn` — the wrapper pins the Maven version.

## Layout

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

## Documentation (`docs/`)

- **`docs/references/`** — living documentation of how the system works, for
  humans and agents. Numbered `NNNNNN-<slug>.md` files (6-digit id) with YAML
  frontmatter as the source of truth and a generated sister table; a generated
  index lives in `docs/references/README.md`. Keep these current as code changes.
  Do **not** hand-edit generated tables or the index, and do **not** hand-write
  frontmatter — use the tooling or the skills. Reference docs are **not** ADRs
  and **not** OpenAPI reference (OpenAPI comes from Spring/springdoc). Every tag
  must exist in `docs/references/_tags.md`.
- **Tooling** lives in `docs/tooling` (Python). Lean on it to save tokens:
  `references_cli.py create|sync|validate|search|meta`. `validate` runs in CI.
  Tests live beside it and must stay ≥80% coverage; ruff and mypy gate it too.
- **Skills:** use `creating-reference-docs`, `updating-reference-docs`, and
  `searching-reference-docs`. Create/update finalize by dispatching the
  `technical-writer` agent, which fixes prose and resolves cross-doc repetition
  or contradiction. The agent runs at authoring time only, never in CI.
- **`docs/superpowers/`** — Superpowers `plans/` and `specs/`. These are
  historical snapshots. After a PR opens, **ignore review comments on
  `docs/superpowers/plans/**` and `docs/superpowers/specs/**`** — the code may
  legitimately diverge, and snapshots are never backfilled. This does not affect
  Superpowers' own plan/spec reviews during the implementation they drive.
- **`docs/ai-prompts/`** — personal scratch for AI prompts. Contents are
  gitignored (directory tracked via `.gitkeep`); excluded from all linters.
- **ADRs** are coming in a future session with their own template, tags, README,
  skills, and CI checks, reusing the same tooling library. Do not pre-empt them.

## Engineering principles

These are the standards this repository holds itself to. Code that violates them
should be fixed, not extended.

### Legible to both humans and agents

Someone — person or model — should be able to open a file and understand it
without reading the rest of the codebase.

- **Names state intent.** `findCollectionByOwner`, not `get2`. `isPublished`, not
  `flag`. A name that needs a comment to explain it is the wrong name.
- **No magic values.** Extract literals to named constants, or to configuration
  in `application.yml` when they differ between environments.
- **Comments explain _why_, never _what_.** The code already says what it does.
  Comment the non-obvious constraint, trade-off, or reason a simpler approach
  does not work.
- **Keep classes and methods small.** A class past roughly 200 lines, or a method
  past roughly 20, is a signal that it holds more than one responsibility.
- **Types are documentation.** Use records for data carriers and precise types
  over `Object` or stringly-typed values. Return `Optional` rather than `null`.
- **Shallow control flow.** Prefer guard clauses and early returns over nested
  conditionals.

### DRY — do not repeat yourself

- Extract duplicated logic into a well-named method or class. Duplicated
  _knowledge_ is the problem, not duplicated characters — two methods that look
  alike but change for different reasons should stay separate.
- **Wait for the third occurrence.** Abstracting two similar blocks prematurely
  creates a wrong abstraction, which is harder to unwind than the duplication
  was.
- Configuration, constants, and types have exactly one home. If a value must
  match something in `zarlania-app`, comment the coupling at both ends.

### SOLID

- **Single responsibility.** A class has one reason to change. Controllers handle
  HTTP concerns only — parsing, validation, status codes. Business rules live in
  services; persistence lives behind repositories.
- **Open/closed.** Extend behaviour by adding a new implementation, not by adding
  another branch to an existing `if`/`switch`. A growing conditional over a type
  code is a signal to use polymorphism or the strategy pattern.
- **Liskov substitution.** An implementation must honour its interface's
  contract. Do not throw `UnsupportedOperationException` from a method the
  interface promises, or tighten preconditions a caller cannot see.
- **Interface segregation.** Keep interfaces narrow and role-based. A caller that
  needs to read collections should not depend on an interface that also writes
  them.
- **Dependency inversion.** Depend on interfaces, not concrete classes, and
  inject them through the constructor. This is what makes services testable
  without a Spring context.

## Conventions

- **Formatting is not a judgement call.** Spotless enforces Google Java Style and
  `./mvnw verify` fails on deviation. Run `./mvnw spotless:apply` rather than
  hand-formatting.
- Constructor injection only — never field injection. It keeps dependencies
  explicit and the class testable without Spring.
- Keep controllers thin; business logic belongs in services.
- **Map routes from the root — no `/api` prefix.** This service is backend-only
  and is deployed at `api.zarlania.com`, so the host already identifies it as the
  API; prefixing every route would repeat that in the path. A controller for
  collections maps `/collections`, not `/api/collections`. The one exception is
  `/actuator/**`, which Spring owns.
- Use records for immutable data carriers such as request and response bodies.
- **Lombok is available, but narrowed.** `@Data`, `@Value`, `@Getter` and
  `@Setter` are compile errors, because a record already does that job and two
  competing ways to declare a data carrier is worse than one. So are
  `@SneakyThrows`, `@val`, `@var` and `@Cleanup`, which hide control flow or
  types. Use `@RequiredArgsConstructor` for constructor injection, `@Slf4j` for
  a logger, and `@Builder` once a constructor takes too many arguments. The full
  list and reasoning is in `lombok.config`.
- Configuration is read from `application.yml` with environment-variable
  overrides. Never hardcode a value that differs between environments.
- Name tests after the behaviour they assert (`applicationBootsAgainstPostgres`),
  not the method under test.
- Prefer Spring Boot test slices (`@WebMvcTest`) over `@SpringBootTest` when the
  full context is not needed — they are dramatically faster.
- **Flyway owns the schema.** Never enable Hibernate DDL generation; write a
  versioned migration instead. Tests run against real Postgres via
  Testcontainers, so Docker must be running for `./mvnw verify`.
- Datasource config comes from `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/
  `DB_PASSWORD`, defaulting to the local compose stack. Any Postgres provider
  plugs in through those five variables — never hardcode a JDBC URL.

## Quality gates

`./mvnw verify` runs five gates, each owning a distinct concern. They do not
overlap, and that is deliberate — a second tool checking the same thing produces
contradictory failures.

| Gate | Owns | Config |
| ---- | ---- | ------ |
| Spotless | Formatting: layout, wrapping, import order | `pom.xml` |
| Checkstyle | Design: size, nesting, complexity, banned constructs | `config/checkstyle/` |
| SpotBugs + FindSecBugs | Bytecode defects and security patterns | `config/spotbugs/` |
| JaCoCo | Line and branch coverage at 80% | `pom.xml` |
| CodeQL | Deeper security analysis, in CI only | `.github/workflows/` |
| yamllint | YAML defects, in CI only | `.yamllint.yml` |
| markdownlint | Markdown structure, in CI only | `.markdownlint-cli2.jsonc` |

The YAML and Markdown linters run in the `Lint` workflow rather than in `verify`,
since neither needs a Java toolchain. Both are pinned to an exact version,
because a new upstream release can enable a rule by default and fail an unrelated
pull request. Dependabot does not track them — bump them by hand.

Run them locally with `npx markdownlint-cli2` and `yamllint --strict -c
.yamllint.yml .`; `markdownlint-cli2 --fix` repairs most Markdown findings.

Checkstyle enforces the numbers behind the principles above: methods stay under
40 lines and files under 400, complexity under 10, nesting under 2, no magic
numbers, no field injection. Those ceilings sit deliberately above the guidance
in _Engineering principles_ — the guidance is the review signal, the gate catches
what has clearly got away from us. **Do not add formatting rules to Checkstyle;
Spotless owns formatting.**

When a gate fires, fix the code. Suppress only when the tool is wrong about that
specific code, and say why:

- Checkstyle: add to `config/checkstyle/suppressions.xml` with a comment.
- SpotBugs: prefer `@SuppressFBWarnings` at the site; use
  `config/spotbugs/exclude.xml` only when the finding is project-wide.

A suppression without a checkable reason is a bug someone will inherit.

## Workflow rules that CI enforces

These are not suggestions; the `PR Lint` workflow fails the build if they are not
followed.

1. **Every change requires a tracking issue.** If there is no issue, one must be
   created before opening a pull request. **File it through one of the issue
   templates** in `.github/ISSUE_TEMPLATE/` — bug report, feature request, or
   chore — and fill in every required field. Blank issues are disabled, so an
   issue written free-form is missing sections the templates require. Use
   `gh issue create --template <bug_report|feature_request|chore>.yml`, and keep
   the template's title prefix (`bug:`, `feat:`, `chore:`).
2. **Branch name:** `<issue-number>-<slug>`, e.g. `42-add-hello-endpoint`.
3. **Pull request title:** `#<issue-number> <type>: <description>`, e.g.
   `#42 feat: add hello endpoint`. Types: `feat`, `fix`, `chore`, `docs`,
   `refactor`, `perf`, `test`, `build`, `ci`, `style`, `revert`.
4. **Pull request body** must contain `Closes #<issue-number>`.
5. All three issue references must match, and the issue must be open.
6. Never commit directly to `master`.
7. Apply a `major`, `minor` or `patch` label — it sets the released version.

## Versioning

The version in `pom.xml` is deliberately frozen at `0.0.1-SNAPSHOT`. **Git tags are
the only source of truth for versions.** Do not bump the POM version; merging to
`master` cuts a release automatically, and the release notes are the changelog
(there is no `CHANGELOG.md`).

## Things to be careful about

- The Render free tier gives 512 MB of memory and no persistent disk. Do not write
  to the local filesystem expecting it to survive a restart.
- `management.endpoints.web.exposure.include` in `application.yml` is intentionally
  limited to `health`. Anything added there is publicly reachable without
  authentication.
- Do not add a `CHANGELOG.md`. Release notes replace it.
- Do not commit secrets — Gitleaks scans full history on every push and pull
  request.
