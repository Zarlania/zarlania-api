# Design: Python coverage PR comment

**Issue:** #55 — PR reporting Python's code coverage
**Date:** 2026-06-28

## Context

CI already checks Python code coverage: the `checks` job in `.github/workflows/ci.yml`
runs `pytest --cov-fail-under=80`, with coverage config (term-missing report, branch
coverage, the 80% floor) living in `pyproject.toml` `addopts`. Today that coverage is
visible **only in the job log** — to see it you must open the CI job and read the output.

The Java side already solves the equivalent problem: the `build` job posts a sticky PR
comment via `madrapps/jacoco-report`, a single pinned action that reads `jacoco.xml` and
comments, with `pull-requests: write` granted at the job level.

This change gives Python the same treatment: a graphical, at-a-glance coverage comment on
each PR, mirroring the established Java pattern.

## Goal

On every pull request, post/update a single sticky comment reporting Python code coverage,
including per-changed-file coverage, **even when the coverage gate fails** (that is exactly
when the number matters most).

## Approach

Mirror the existing Java JaCoCo step using `5monkeys/cobertura-action` (chosen over
`py-cov-action/python-coverage-comment-action`, which persists data to an orphan branch and
is fussier for fork PRs, and over hand-rolled markdown, which is more surface to maintain).
It is the one-to-one structural twin of the Java step: one pinned action, one XML in, one
sticky comment out, no repo side effects.

### 1. Emit a Cobertura XML report

In `.github/workflows/ci.yml`, change the `checks` job's test step to:

```
pytest --cov-report=xml
```

This **adds** an XML report on top of the existing `term-missing` and `--cov-fail-under=80`
already in `pyproject.toml` `addopts`; the 80% gate and console output are unchanged. The
redundant `--cov-fail-under=80` currently passed on the CLI is dropped (it already lives in
`addopts`).

Add `coverage.xml` to `.gitignore` so local runs leave no artifact.

### 2. Grant the `checks` job comment permission

Add a job-level `permissions` block to `checks`, matching the `build` job:

```yaml
permissions:
  contents: read
  pull-requests: write # for the Python coverage PR comment
```

### 3. Post the comment

Add a PR-only step to the `checks` job:

```yaml
- name: Python coverage report comment
  if: ${{ !cancelled() && github.event_name == 'pull_request' && hashFiles('coverage.xml') != '' }}
  uses: 5monkeys/cobertura-action@<pinned-sha> # <version>
  with:
    path: coverage.xml
    repo_token: ${{ secrets.GITHUB_TOKEN }}
    minimum_coverage: 80      # display/badge only
    fail_below_threshold: false
    only_changed_files: true
    show_branch: true
    report_name: Python Coverage
```

- `pytest --cov-fail-under=80` remains the **single** hard gate; `fail_below_threshold:
  false` keeps the comment informational so coverage is never gated in two places.
- The `if:` guard is the robust form of `always()`: it posts the comment even on a failing
  run, but skips (rather than errors) on failure types where `coverage.xml` was never
  written.
- Pin the action to a full commit SHA with a trailing `# vX.Y.Z` comment, per the existing
  convention for third-party actions in this repo.

### 4. Apply the same `always()` fix to the Java JaCoCo step

Change the existing `JaCoCo coverage report comment` step's condition from
`github.event_name == 'pull_request'` to the same guarded form:

```yaml
if: ${{ !cancelled() && github.event_name == 'pull_request' && hashFiles('**/jacoco.xml') != '' }}
```

so the Java comment also posts on failing runs whenever its report exists.

## Out of scope / decisions

- **No ADR.** This mirrors an already-accepted pattern (the JaCoCo comment step, which has
  no dedicated ADR), is CI dev-tooling, and adds no dependency to the shipped service.
- No change to the 80% threshold or to which Python modules are measured.
- No badge persistence, no separate data branch, no fork-PR two-workflow split.

## Verification

- **Static:** `actionlint` (run via pre-commit) passes on the edited workflow; YAML guards
  parse.
- **Local report generation:** `pytest --cov-report=xml` produces a valid `coverage.xml`
  at the repo root, and `coverage.xml` is git-ignored (`git status` clean).
- **End-to-end:** open the PR for this branch and confirm:
  1. A "Python Coverage" sticky comment appears, showing overall + changed-file coverage.
  2. The "Lint & ADR tests" check still passes (coverage ≥ 80%).
  3. (Manual check) temporarily dropping coverage below 80% on a scratch branch makes the
     check go red **and** still posts the comment with the sub-80% number.
  4. The Java "JaCoCo Coverage" comment still posts as before.
