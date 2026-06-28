# Python Coverage PR Comment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Post a sticky PR comment reporting Python code coverage on every pull request, mirroring the existing Java JaCoCo comment.

**Architecture:** The `checks` job in `.github/workflows/ci.yml` already runs `pytest` with an 80% coverage floor. We add a Cobertura XML report, grant the job `pull-requests: write`, and post the report via the pinned `5monkeys/cobertura-action`. We also harden the existing Java JaCoCo comment so both comments post even on failing runs.

**Tech Stack:** GitHub Actions, pytest + pytest-cov (Cobertura XML), `5monkeys/cobertura-action`.

## Global Constraints

- This is a live public service: merges to `master` deploy to production. CI changes only here — no application code.
- **Pin all third-party actions to a full commit SHA** with a trailing `# vX.Y.Z` comment (existing repo convention).
- **Never silence the gates.** `pytest --cov-fail-under=80` stays the single hard coverage gate; the comment is informational (`fail_below_threshold: false`).
- Every merge cuts one SemVer release; the version is bumped **inside this PR**. This is a chore/CI change → **patch** bump (no `release:` label = patch).
- Branch: `ci/55-python-coverage-pr-comment` (already created). PR title must reference `#55`.
- The spec for this work: `docs/superpowers/specs/2026-06-28-python-coverage-pr-comment-design.md`.

## File Structure

- Modify: `.github/workflows/ci.yml` — `checks` job (XML report, permissions, comment step) and `build` job (Java comment guard).
- Modify: `.gitignore` — ignore `coverage.xml`.
- Modify: `pom.xml` — `<version>` patch bump (via script).
- Commit (already created): the spec file under `docs/superpowers/specs/`.

**Verification reality:** A CI workflow change cannot be unit-tested with pytest. The proof points are (1) `actionlint` (runs under pre-commit) accepts the YAML, (2) `pytest --cov-report=xml` produces a valid `coverage.xml` locally, and (3) the actual CI run on this PR posts the comment. Tasks use these as their "test" steps.

---

### Task 1: Generate a Cobertura XML report and git-ignore it

**Files:**
- Modify: `.github/workflows/ci.yml` (the `checks` job's `ADR tooling tests` step, line ~88-89)
- Modify: `.gitignore` (Python section)

**Interfaces:**
- Produces: a `coverage.xml` (Cobertura format) at the repo root after `pytest` runs in the `checks` job. Task 2 consumes it.

- [ ] **Step 1: Add `coverage.xml` to `.gitignore`**

In the `### Python ###` block of `.gitignore`, add a line after `.coverage`:

```
### Python ###
.venv/
__pycache__/
*.py[cod]
.coverage
coverage.xml
.pytest_cache/
*.egg-info/
```

- [ ] **Step 2: Emit the XML report in CI**

In `.github/workflows/ci.yml`, change the `checks` job test step from:

```yaml
      - name: ADR tooling tests
        run: pytest --cov-fail-under=80
```

to:

```yaml
      - name: ADR tooling tests
        run: pytest --cov-report=xml
```

(The `--cov-fail-under=80` floor and `term-missing` report already live in `pyproject.toml` `addopts`; the CLI `--cov-report=xml` adds the Cobertura file on top.)

- [ ] **Step 3: Verify the report is generated locally and is ignored**

Run:

```bash
pip install -r requirements-dev.txt
pytest --cov-report=xml
ls -l coverage.xml
git status --porcelain coverage.xml
```

Expected: `coverage.xml` exists at repo root; `git status --porcelain coverage.xml` prints **nothing** (it's ignored). Coverage still passes the 80% floor (pytest exits 0).

- [ ] **Step 4: Commit**

```bash
git add .gitignore .github/workflows/ci.yml
git commit -m "ci: emit Cobertura XML coverage report for Python (#55)"
```

---

### Task 2: Post the Python coverage PR comment

**Files:**
- Modify: `.github/workflows/ci.yml` (the `checks` job)

**Interfaces:**
- Consumes: `coverage.xml` at repo root (from Task 1).
- Produces: a sticky PR comment titled "Python Coverage".

- [ ] **Step 1: Grant the `checks` job comment permission**

Add a job-level `permissions` block to the `checks` job, immediately under `runs-on: ubuntu-latest` (mirroring the `build` job at lines 22-24):

```yaml
  checks:
    name: Lint & ADR tests
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write # for the Python coverage PR comment
    steps:
```

- [ ] **Step 2: Add the comment step**

Append this step to the end of the `checks` job's `steps:` (after the `ADR tooling tests` step):

```yaml
      - name: Python coverage report comment
        if: ${{ !cancelled() && github.event_name == 'pull_request' && hashFiles('coverage.xml') != '' }}
        uses: 5monkeys/cobertura-action@ee5787cc56634acddedc51f21c7947985531e6eb # v14
        with:
          path: coverage.xml
          repo_token: ${{ secrets.GITHUB_TOKEN }}
          minimum_coverage: 80
          fail_below_threshold: false
          only_changed_files: true
          show_branch: true
          report_name: Python Coverage
```

- [ ] **Step 3: Verify the workflow lints**

Run:

```bash
pre-commit run actionlint --files .github/workflows/ci.yml
```

Expected: `actionlint` passes (Passed). If `actionlint` is not a pre-commit hook id, run `pre-commit run --files .github/workflows/ci.yml` and confirm no YAML/workflow errors.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: post Python coverage comment on PRs (#55)"
```

---

### Task 3: Make the Java JaCoCo comment post on failing runs

**Files:**
- Modify: `.github/workflows/ci.yml` (the `build` job's `JaCoCo coverage report comment` step, line ~39-40)

**Interfaces:** none new.

- [ ] **Step 1: Update the JaCoCo step's condition**

In `.github/workflows/ci.yml`, change:

```yaml
      - name: JaCoCo coverage report comment
        if: github.event_name == 'pull_request'
```

to:

```yaml
      - name: JaCoCo coverage report comment
        if: ${{ !cancelled() && github.event_name == 'pull_request' && hashFiles('**/jacoco.xml') != '' }}
```

(So the Java comment, like the Python one, posts even when `mvn verify` fails — but only when the report was actually written.)

- [ ] **Step 2: Verify the workflow lints**

Run:

```bash
pre-commit run actionlint --files .github/workflows/ci.yml
```

Expected: passes.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: post JaCoCo coverage comment on failing runs too (#55)"
```

---

### Task 4: Commit the spec and bump the release version

**Files:**
- Commit: `docs/superpowers/specs/2026-06-28-python-coverage-pr-comment-design.md`, `docs/superpowers/plans/2026-06-28-python-coverage-pr-comment.md`
- Modify: `pom.xml` (`<version>`, via script)

- [ ] **Step 1: Bump the version (patch)**

Run:

```bash
./scripts/bump-version bump patch
```

Expected: `pom.xml` `<version>` advances by one patch level over the latest release tag. (Confirm with `./scripts/bump-version` help if the subcommand differs; per CLAUDE.md the command is `./scripts/bump-version bump <kind>`.)

- [ ] **Step 2: Commit the spec, plan, and version bump**

```bash
git add docs/superpowers/specs/2026-06-28-python-coverage-pr-comment-design.md \
        docs/superpowers/plans/2026-06-28-python-coverage-pr-comment.md \
        pom.xml
git commit -m "docs: spec/plan for Python coverage PR comment; bump version (#55)"
```

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin ci/55-python-coverage-pr-comment
gh pr create --title "ci: report Python code coverage as a PR comment (#55)" \
  --body "Closes #55. Adds a Cobertura XML report + sticky 'Python Coverage' comment to the checks job, mirroring the Java JaCoCo comment, and makes both comments post on failing runs." \
  --label "release:patch"
```

Expected: PR opens; the "Release version bump" check passes (pom matches the patch label).

---

## End-to-end verification (on the open PR)

1. The **"Python Coverage"** sticky comment appears on the PR, showing overall coverage and the changed-files table.
2. The **"Lint & ADR tests"** check is green (coverage ≥ 80%).
3. The **"Release version bump"** check is green.
4. The Java **"JaCoCo Coverage"** comment still posts as before.
5. (Optional manual check) On a throwaway commit that drops Python coverage below 80%, confirm the `checks` job goes **red** and the "Python Coverage" comment still posts with the sub-80% number — then revert.

## Self-Review

- **Spec coverage:** XML report (Task 1) ✓; job permission + comment step (Task 2) ✓; single hard gate via `fail_below_threshold: false` (Task 2) ✓; `coverage.xml` git-ignored (Task 1) ✓; Java `always()` fix (Task 3) ✓; no ADR (intentional, noted in spec) ✓; release bump (Task 4) ✓.
- **Placeholder scan:** action pinned to real SHA `ee5787c` / v14; no TBDs.
- **Input names** verified against `action.yml` v14: `path`, `repo_token`, `minimum_coverage`, `fail_below_threshold`, `only_changed_files`, `show_branch`, `report_name`.
