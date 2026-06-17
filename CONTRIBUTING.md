# Contributing to Zarlania API

This is a live, public service: merges to `master` deploy to production at
<https://api.zarlania.com>. Work carefully and follow the workflow below.

## Workflow

1. **Every change starts with a GitHub issue.** No issue, no change.
2. **Branch off `master`**, named `type/<issue#>-slug` (e.g. `feat/12-add-widgets`,
   `fix/34-cors-origin`). Types: `feat`, `fix`, `chore`, `docs`, `refactor`, `test`.
3. **Open a PR** whose title references the issue (`#<issue>`). Fill in the PR template.
4. **CI must pass** (build/quality gates, lint, ADR checks, secret scan, governance).
5. **Code owner approval** is required before merge (you, via CODEOWNERS).
6. **Squash-merge to `master`**, which auto-deploys to Render.

## Releasing

Releases are automated — every merge to `master` produces one SemVer release; there is no
manual release step and no `CHANGELOG.md` (GitHub Release notes are auto-generated).

The version is `pom.xml` `<version>` and must be bumped **in the PR**:

1. Apply one `release:major`, `release:minor`, or `release:patch` label (no label = patch).
2. Run `./scripts/bump-version bump <kind>` to write the next version.
3. CI rejects the PR if the pom version doesn't match the label relative to the latest
   release tag.

On merge, `release.yml` creates the `v<version>` tag and GitHub Release. Dependabot PRs are
not expected to bump; their changes are included in the next release that does.

## Architecture Decision Records

Significant decisions are recorded as ADRs in `docs/adrs/` and are binding once accepted.
See `docs/adrs/0001-record-architecture-decisions.md`. Use `./scripts/adr` to browse,
create, and validate them.

## Local setup & checks

```bash
./scripts/setup-dev      # venv + dev deps + git hooks
./scripts/check          # fast checks (pre-commit on all files)
./scripts/check --full   # also runs ./mvnw clean verify (tests, SpotBugs, JaCoCo)
```

Never commit secrets. They live only in Render environment variables and local `.env`
(git-ignored).

## Branch protection (maintainer setup)

Apply once in **GitHub → Settings → Branches → Add branch ruleset** (or classic
"Branch protection rules") for `master`:

1. **Require a pull request before merging** — require **1 approval**, and
   **Require review from Code Owners**.
2. **Require status checks to pass before merging** — add these checks (they appear
   after CI has run once on a PR): `Build & quality gates`, `Lint & ADR tests`,
   `Secret scan`, `PR references an issue`.
3. **Require branches to be up to date before merging.**
4. **Do not allow bypassing the above settings** / **Block force pushes** to `master`.
5. Leave **Allow squash merging** enabled (disable merge commits/rebase if you prefer).
