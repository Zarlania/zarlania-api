---
id: '0008'
name: Require issue-driven contribution workflow
description: 'Issue-driven branches/PRs enforced in CI, with templates, CODEOWNERS, and branch protection.'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- governance
- process
---
# ADR-0008: Require issue-driven contribution workflow

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0008 |
| Name | Require issue-driven contribution workflow |
| Description | Issue-driven branches/PRs enforced in CI, with templates, CODEOWNERS, and branch protection. |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | governance, process |
<!-- adr-meta:end -->

## Context and Problem Statement

`master` deploys to production, so every change must be traceable to an issue, reviewed, and
gated. The workflow must be enforced, not merely documented.

## Decision Drivers

- Traceability: every change ties to a GitHub issue.
- Consistent branch/PR naming that tooling can parse.
- Owner review and protected `master`.

## Considered Options

- Issue-driven workflow enforced by a CI governance check (chosen).
- Convention-by-documentation only (not enforced).

## Decision Outcome

Chosen: **issue-driven workflow.** Branches are `type/<issue#>-slug`; PR titles reference
`#<issue>`; a CI "governance" job fails any human PR that references no issue (Dependabot
exempt). Issue templates (bug/feature/chore) and a PR template carry the issue-ref,
release-label, ADR, and secrets checklists. `CODEOWNERS` is `* @stimothy`. `master` is
protected (require PR, passing checks, 1 codeowner approval, no direct pushes) — configured
manually in GitHub and documented in `README.md`/`CONTRIBUTING.md`.

### Consequences

- Good: every change is traceable, reviewed, and gated; naming is machine-checkable.
- Bad: branch protection is manual GitHub state, not code — it must be kept in sync by hand.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` §5
- `.github/workflows/ci.yml`, `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md`,
  `.github/CODEOWNERS`, `CONTRIBUTING.md`
