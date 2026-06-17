# CLAUDE.md — Zarlania API

AI entry point for this repo. **This is a live, public service: merges to `master`
deploy to production at <https://api.zarlania.com>.** Work carefully.

## Non-negotiables

- **Never commit secrets.** No credentials, tokens, or keys in any commit. Secrets live
  only in Render environment variables and local `.env` (git-ignored).
- **ADRs are law.** Code may not contradict an accepted ADR without a new ADR that
  supersedes it. See `docs/adrs/0001-record-architecture-decisions.md`.
- **Every change ties to a GitHub issue.** Branch `type/<issue#>-slug`; PR title
  references `#<issue>`.

## Stack

Java 25 / Spring Boot 4.1.x / Maven (wrapper `./mvnw`) / multi-stage Temurin Docker.
For the version-adoption rationale see ADR-0006 (`./scripts/adr show 0006`).

## Releases (every merge ships)

Every merge to `master` cuts exactly one SemVer release. The version lives in `pom.xml`
`<version>` and is bumped **inside the PR** (never after merge — that would double-deploy).

When opening a PR:

1. Choose the bump from the change: breaking = `major`, feature = `minor`, fix/chore =
   `patch`. Apply the matching `release:<kind>` label (no label = `patch`).
2. Run `./scripts/bump-version bump <kind>` to set `pom.xml` to the next version.
3. CI's "Release version bump" check verifies the pom matches the label vs. the latest
   release tag; on merge, `release.yml` tags `v<version>` and cuts the GitHub Release.

## Working with ADRs (save tokens)

To find an ADR or check whether a decision exists, **use the `adr-search` skill / the
CLI — do not scan `docs/adrs/` by hand**:

- `./scripts/adr list` / `./scripts/adr find "<query>"` / `./scripts/adr show <id>`

To create one, use the `adr-create` skill. For tags, use `adr-tags`. Run
`./scripts/adr check` after any ADR change.

## Status

All five phases are shipped:

- **Phase 1** — ADRs: ADR-0001 through ADR-0009 accepted, covering the ADR process,
  actuator, OpenAPI, CORS, deployment, stack, quality gates, contribution workflow, and
  release model.
- **Phase 2** — Quality gates: Spotless, Checkstyle, SpotBugs/FindSecBugs, JaCoCo
  (≥ 80% line + branch), pre-commit hooks, `./scripts/check`.
- **Phase 3** — CI/governance: `.github/workflows/ci.yml` enforces all gates on every PR.
- **Phase 4** — App shell: Actuator (`health`, `info`), liveness/readiness probe groups,
  build-info at `/actuator/info`, springdoc OpenAPI + Swagger UI, env-driven CORS allowlist.
- **Phase 5** — Release automation: `release.yml` tags `v<version>` and cuts a GitHub
  Release on every merge to `master` (#28/#29).
