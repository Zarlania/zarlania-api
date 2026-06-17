# Zarlania API

Backend service for Zarlania, deployed at <https://api.zarlania.com>.
Java 25 / Spring Boot 4.1.x / Maven, containerized on Render.

## Prerequisites

- **JDK 25** (e.g. Eclipse Temurin 25)
- **Docker** (for `docker compose` local run)
- **Python 3** (for dev tooling — pre-commit, ADR CLI)

## Developer setup

Run once after cloning to install pre-commit hooks and dev dependencies:

```bash
./scripts/setup-dev
```

Then use `./scripts/check` to run the fast local check suite (mirrors pre-commit),
or `./scripts/check --full` to also run the full Maven verify.

## Build and test

```bash
./mvnw verify    # Spotless + Checkstyle + SpotBugs/FindSecBugs + JaCoCo (≥80%) + tests
```

## Running locally

**Docker Compose (recommended):**

```bash
docker compose up --build    # serves on http://localhost:8080
```

**Maven directly:**

```bash
./mvnw spring-boot:run       # serves on http://localhost:8080
```

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Aggregate health (used by Render health check) |
| `GET /actuator/health/liveness` | Liveness probe |
| `GET /actuator/health/readiness` | Readiness probe |
| `GET /actuator/info` | Build info and version |
| `GET /swagger-ui.html` | Swagger UI (interactive API docs) |
| `GET /v3/api-docs` | OpenAPI JSON spec |

Health details are never exposed publicly; only `health` and `info` are accessible
over HTTP. See ADR-0002 and ADR-0003.

## Architecture Decision Records

Significant decisions are recorded as ADRs in `docs/adrs/`. Browse them with the CLI:

```bash
./scripts/adr list            # list all ADRs
./scripts/adr find "<query>"  # search ADRs
./scripts/adr show 0006       # show one ADR's metadata
./scripts/adr check           # validate ADRs (no drift, valid tags, fresh index)
```

ADRs 0001–0009 are in place covering the architecture, stack, quality gates,
contribution workflow, and release model. The ADR process itself is defined in
`docs/adrs/0001-record-architecture-decisions.md`.

## Contribution workflow

Every change must tie to a GitHub issue:

1. Open or pick up an issue.
2. Branch: `type/<issue#>-short-slug` (e.g. `feat/42-add-users-endpoint`).
3. Open a PR with the issue number in the title (e.g. `feat: add users endpoint (#42)`).
4. Apply a `release:<kind>` label (see Release section below).
5. Run `./scripts/bump-version bump <kind>` to set the version in `pom.xml`.
6. CI must pass before merge.

See ADR-0008 for the full contribution policy.

## Release model

Every merge to `master` cuts a SemVer release automatically:

1. Choose the bump: breaking → `major`, new feature → `minor`, fix/chore → `patch`
   (unlabeled defaults to `patch`).
2. Apply the matching `release:major|minor|patch` label to the PR.
3. Run `./scripts/bump-version bump <kind>` inside the PR to update `pom.xml`.
4. On merge, `.github/workflows/release.yml` tags `v<version>` and publishes a
   GitHub Release.

See ADR-0009 for the release policy rationale.

## Maintainer: one-time branch-protection setup

Create the bump labels if they do not exist:

```bash
gh label create release:major --color B60205 --description "Release: major version bump"
gh label create release:minor --color FBCA04 --description "Release: minor version bump"
gh label create release:patch --color 0E8A16 --description "Release: patch version bump"
```

Add **Release version bump** and the existing CI jobs to the required status checks for
`master` (Settings → Branches → branch protection rule).
