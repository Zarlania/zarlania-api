# CLAUDE.md

Guidance for AI coding agents working in this repository. This file is the
canonical set of agent instructions; `AGENTS.md` points here.

## What this is

`zarlania-api` is the open-source backend for Zarlania — a Spring Boot service
written in Java. The browser client lives in a separate repository,
[Zarlania/zarlania-app](https://github.com/Zarlania/zarlania-app).

> **Status: early scaffolding.** The service currently exposes a single
> hello-world endpoint. There is no domain model, persistence layer, or
> authentication yet.
>
> **PLACEHOLDER — expand as the project takes shape:** domain concepts and
> vocabulary, module boundaries, persistence and migration strategy, authentication
> and authorization model, external integrations, and the API versioning policy.

## Stack

| Concern    | Choice                                     |
| ---------- | ------------------------------------------ |
| Language   | Java 25 (Temurin)                          |
| Framework  | Spring Boot 4.1                            |
| Build      | Maven, via the committed `./mvnw` wrapper  |
| Formatting | Spotless with Google Java Style            |
| Testing    | JUnit 5 and Spring Boot test slices        |
| Container  | Docker, with Compose for local development |
| Hosting    | Render, configured in `render.yaml`        |

## Commands

| Command                  | Purpose                                                  |
| ------------------------ | -------------------------------------------------------- |
| `./mvnw verify`          | Compile, test, and check formatting. **This is what CI runs.** |
| `./mvnw test`            | Tests only.                                              |
| `./mvnw spotless:apply`  | Reformat. Run this before committing.                    |
| `./mvnw spring-boot:run` | Run locally on port 8080.                                |
| `docker compose up --build` | Run in a container.                                   |

Always use `./mvnw`, never a system `mvn` — the wrapper pins the Maven version.

## Layout

```
src/main/java/com/zarlania/api/
  ZarlaniaApiApplication.java   Entry point
  hello/                        Feature package: controller + response record
src/main/resources/
  application.yml               Configuration, with env-var overrides
src/test/java/com/zarlania/api/  Tests, mirroring the main package structure
```

Code is organised by feature, not by layer — a feature's controller, service and
model live together in one package. Do not create top-level `controllers/`,
`services/` or `models/` packages.

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
  *knowledge* is the problem, not duplicated characters — two methods that look
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
- Use records for immutable data carriers such as request and response bodies.
- Configuration is read from `application.yml` with environment-variable
  overrides. Never hardcode a value that differs between environments.
- Name tests after the behaviour they assert (`helloReturnsGreeting`), not the
  method under test.
- Prefer Spring Boot test slices (`@WebMvcTest`) over `@SpringBootTest` when the
  full context is not needed — they are dramatically faster.

## Workflow rules that CI enforces

These are not suggestions; the `PR Lint` workflow fails the build if they are not
followed.

1. **Every change requires a tracking issue.** If there is no issue, one must be
   created before opening a pull request. **File it through one of the issue
   templates** in `.github/ISSUE_TEMPLATE/` — bug report, feature request, or
   chore — and fill in every required field. Blank issues are disabled, so an
   issue written free-form is missing sections the templates require. Use
   `gh issue create --template <bug_report|feature_request|chore>.yml`, and keep
   the template's title prefix (`bug: `, `feat: `, `chore: `).
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
