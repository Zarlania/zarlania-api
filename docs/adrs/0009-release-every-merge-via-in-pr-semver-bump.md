---
id: 0009
name: Release every merge via in-PR SemVer bump
description: SemVer in pom.xml, label-driven in-PR bump, automated tag + GitHub Release
  on merge, single deploy.
status: accepted
date_proposed: '2026-06-16'
date_accepted: '2026-06-16'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- deployment
- release
---
# ADR-0009: Release every merge via in-PR SemVer bump

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0009 |
| Name | Release every merge via in-PR SemVer bump |
| Description | SemVer in pom.xml, label-driven in-PR bump, automated tag + GitHub Release on merge, single deploy. |
| Status | accepted |
| Date proposed | 2026-06-16 |
| Date accepted | 2026-06-16 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | deployment, release |
<!-- adr-meta:end -->

## Context and Problem Statement

Every merge to `master` deploys once to production. We want each merge to also cut exactly
one SemVer release with no manual steps and no double deploy.

## Decision Drivers

- The merge commit must be the only push (Render deploys once) — no post-merge bump commit.
- Releasing must be automatic and hard to get wrong.
- No separately maintained changelog file.

## Considered Options

- In-PR version bump + automated tag/Release on merge (chosen).
- Post-merge bump commit (causes a second deploy — rejected).
- Manual tagging/releases (error-prone — rejected).

## Decision Outcome

Chosen: **SemVer lives in `pom.xml <version>`** (surfaced at `/actuator/info`). A
`release:major|minor|patch` label selects the bump (unlabeled = patch); the bump is
committed **inside the PR** (`./scripts/bump-version`). A CI "Release version bump" check
validates the in-PR version against the label relative to the latest release tag. On merge,
`release.yml` creates the `v<version>` tag and a GitHub Release with auto-generated notes (no
`CHANGELOG.md`). Dependabot PRs are exempt and ride the next human release.

### Consequences

- Good: one merge = one deploy = one release; releasing is automatic and validated.
- Bad: contributors must remember the label/bump (mitigated by the CI check and PR template);
  release notes quality depends on PR titles.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` §6
- `pom.xml`, `scripts/bump-version`, `scripts/release_tool/`, `.github/workflows/release.yml`
