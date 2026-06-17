---
id: '0007'
name: Enforce code quality and security gates
description: 'Maven-driven quality gates with a fast pre-commit vs full-CI split.'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- quality
- security
---
# ADR-0007: Enforce code quality and security gates

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0007 |
| Name | Enforce code quality and security gates |
| Description | Maven-driven quality gates with a fast pre-commit vs full-CI split. |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | quality, security |
<!-- adr-meta:end -->

## Context and Problem Statement

Every change to a live public service must pass consistent format, lint, static-analysis,
security, and coverage gates — fast feedback locally, full enforcement in CI.

## Decision Drivers

- Fast pre-commit (no compilation/tests) so commits stay cheap.
- Authoritative, comprehensive gates in CI before merge/deploy.
- One coverage bar across languages; secrets can never land in a commit.

## Considered Options

- Maven-driven gates + pre-commit/CI split (chosen).
- Run everything in pre-commit (too slow).
- CI-only (loses fast local feedback).

## Decision Outcome

Chosen gates: **Spotless (google-java-format) + Checkstyle (google_checks)** run fast in
pre-commit and CI; **SpotBugs (effort Max) + FindSecBugs 1.14.0 + JaCoCo (line AND branch
≥ 80%) + tests** run in CI via `./mvnw verify` (compilation-bound, CI-only). Pre-commit also
runs gitleaks, markdownlint, shellcheck/shfmt, ruff, yamllint, `./scripts/adr check`, and
hygiene hooks. CI posts a JaCoCo coverage comment to the PR.

Accepted divergences from the spec: the ADR/release tooling is implemented in **Python
(tested with pytest + coverage ≥ 80), not bash — so there are no bats/kcov tests**; and CI
gitleaks scans the **PR commit range** rather than full history, because the repository was
created with clean history and pre-commit blocks new secrets at staging time.

### Consequences

- Good: fast local loop, strong CI enforcement, coverage visible inline, secrets blocked
  at two layers.
- Bad: heavy gates only surface in CI (slower than local for those); per-language coverage
  config must be maintained as new script languages appear.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` §4
- `pom.xml`, `.pre-commit-config.yaml`, `.github/workflows/ci.yml`
