# Phase 3 — CI & Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GitHub Actions CI that runs the Phase 2 gates on every PR (with a JaCoCo coverage PR comment), plus repo governance: CODEOWNERS, issue/PR templates, Dependabot, open-source health files, a CONTRIBUTING guide with branch-protection steps, and a CI check that every PR references an issue.

**Architecture:** One `ci.yml` workflow with focused jobs — `build` (JDK 25 `./mvnw verify` + coverage comment), `checks` (Python: pre-commit lint hooks + ADR pytest), `secrets` (gitleaks on the PR commit range only), and `governance` (PR references an issue). Governance/community files live under `.github/` and the repo root. Branch protection is documented (applied manually by the maintainer).

**Tech Stack:** GitHub Actions, Temurin JDK 25, Maven wrapper, Python `pre-commit`/pytest, gitleaks (binary), Dependabot, Markdown.

**Process:** Built on a feature branch off `master`, tied to a GitHub issue, merged via PR. The authoritative test of this phase is the CI run going green on the PR itself.

**Spec:** `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` (§4.3, §5 partial, §6, §9, §11, §13 Phase 3).

---

## Scope decisions (locked with maintainer)

- **release.yml is deferred to Phase 4** (it depends on the pom version source + `bump-version` + release labels built there). Not in this plan.
- **Branch protection is documented only** (maintainer applies it in GitHub settings). Steps go in `CONTRIBUTING.md`.
- **CI governance enforces an issue reference now**; release-label validation is added in Phase 4. Issue/PR templates ship now.
- **gitleaks-action is NOT used** (it requires a paid license for org-owned repos). CI installs the gitleaks binary and scans only the PR commit range — consistent with the "new changes only" secret-scanning policy.

---

## Important context for the implementer

- Phase 1+2 are merged to `master`: `./scripts/adr` CLI, `scripts/adr_tool/` (pytest, pyproject `pythonpath`/coverage config), `.pre-commit-config.yaml` (top-level `exclude: '^docs/superpowers/'`; local hooks `java-quality` and `adr-check`; gitleaks hook scans staged only), Maven gates in `pom.xml`, `scripts/setup-dev`, `scripts/check`, `requirements-dev.txt` (includes `pre-commit`), `.markdownlint.yaml`, `.yamllint.yaml`.
- Local toolchain: JDK 25, Maven wrapper, Python venv `.venv`. New markdown/yaml files WILL be linted by pre-commit — they must pass `.markdownlint.yaml`/`.yamllint.yaml`.
- `.yamllint.yaml` already sets `truthy.check-keys: false`, so GitHub Actions `on:` keys won't trip yamllint.
- The maintainer's GitHub login is `stimothy`; security contact email is `steven.timothy265@gmail.com`.
- Run `./scripts/check` after changes to validate locally; the real CI validation happens when the PR runs.

---

## File Structure

| File | Responsibility |
|---|---|
| `.github/workflows/ci.yml` | CI: build/quality gates, lint+ADR tests, secret scan, PR-governance. |
| `.github/CODEOWNERS` | `* @stimothy`. |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | Bug report form. |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Feature request form. |
| `.github/ISSUE_TEMPLATE/chore.yml` | Chore/maintenance form. |
| `.github/ISSUE_TEMPLATE/config.yml` | Issue chooser config (disable blank issues). |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR checklist (issue ref, secrets, ADR, tests). |
| `.github/dependabot.yml` | Dependabot: maven, github-actions, pip, docker (weekly). |
| `CONTRIBUTING.md` | Contribution workflow + branch-protection setup steps. |
| `CODE_OF_CONDUCT.md` | Contributor Covenant 2.1. |
| `SECURITY.md` | Vulnerability disclosure policy. |
| `SUPPORT.md` | Where to get help. |

---

## Task 0: Issue + branch

- [ ] **Step 1: Create the issue**

```bash
gh issue create \
  --title "Phase 3: CI & governance (GitHub Actions, CODEOWNERS, templates, Dependabot, OSS files)" \
  --body "Implements Phase 3 of the repo-shell spec (§4.3, §6, §9, §11): ci.yml (mvnw verify + coverage comment, pre-commit lint + ADR pytest, gitleaks PR-range scan, issue-reference governance), CODEOWNERS, issue/PR templates, dependabot.yml, CODE_OF_CONDUCT/SECURITY/SUPPORT, and CONTRIBUTING with branch-protection steps. release.yml deferred to Phase 4."
```

Note the issue number (`<ISSUE#>` below).

- [ ] **Step 2: Branch**

```bash
git switch -c "feat/<ISSUE#>-ci-governance"
```

---

## Task 1: CODEOWNERS

**Files:** Create `.github/CODEOWNERS`.

- [ ] **Step 1: Create `.github/CODEOWNERS`**

```text
# Default owner for everything in the repo.
* @stimothy
```

- [ ] **Step 2: Commit**

```bash
git add .github/CODEOWNERS
git commit -m "chore: add CODEOWNERS (#<ISSUE#>)"
```

---

## Task 2: Open-source health files

**Files:** Create `CODE_OF_CONDUCT.md`, `SECURITY.md`, `SUPPORT.md`.

- [ ] **Step 1: Create `CODE_OF_CONDUCT.md`** (Contributor Covenant 2.1, trimmed enforcement contact to the maintainer)

```markdown
# Contributor Covenant Code of Conduct

## Our Pledge

We as members, contributors, and leaders pledge to make participation in our
community a harassment-free experience for everyone, regardless of age, body size,
visible or invisible disability, ethnicity, sex characteristics, gender identity and
expression, level of experience, education, socio-economic status, nationality,
personal appearance, race, religion, or sexual identity and orientation.

We pledge to act and interact in ways that contribute to an open, welcoming, diverse,
inclusive, and healthy community.

## Our Standards

Examples of behavior that contributes to a positive environment include demonstrating
empathy and kindness, being respectful of differing opinions, giving and gracefully
accepting constructive feedback, and focusing on what is best for the community.

Unacceptable behavior includes harassment, trolling, insulting or derogatory comments,
public or private harassment, publishing others' private information without permission,
and other conduct which could reasonably be considered inappropriate in a professional
setting.

## Enforcement Responsibilities

Community leaders are responsible for clarifying and enforcing our standards and will
take appropriate and fair corrective action in response to any behavior they deem
inappropriate, threatening, offensive, or harmful.

## Scope

This Code of Conduct applies within all community spaces and when an individual is
officially representing the community in public spaces.

## Enforcement

Instances of abusive, harassing, or otherwise unacceptable behavior may be reported to
the maintainer at <steven.timothy265@gmail.com>. All complaints will be reviewed and
investigated promptly and fairly.

## Attribution

This Code of Conduct is adapted from the [Contributor Covenant][homepage], version 2.1,
available at <https://www.contributor-covenant.org/version/2/1/code_of_conduct.html>.

[homepage]: https://www.contributor-covenant.org
```

- [ ] **Step 2: Create `SECURITY.md`**

```markdown
# Security Policy

## Reporting a Vulnerability

**Do not open a public issue for security vulnerabilities.**

Please report security issues privately via GitHub's
[Private Vulnerability Reporting](https://github.com/Zarlania/zarlania-api/security/advisories/new)
("Report a vulnerability" under the Security tab). If you cannot use that, email
<steven.timothy265@gmail.com>.

We will acknowledge your report as soon as possible and keep you informed of progress
toward a fix. Please give us a reasonable opportunity to address the issue before any
public disclosure.

## Scope

This policy covers the `zarlania-api` service and its source in this repository.
```

- [ ] **Step 3: Create `SUPPORT.md`**

```markdown
# Support

Need help with Zarlania API?

- **Bugs / feature requests:** open a [GitHub issue](https://github.com/Zarlania/zarlania-api/issues/new/choose).
- **Security issues:** see [SECURITY.md](SECURITY.md) — do not file a public issue.
- **Contributing:** see [CONTRIBUTING.md](CONTRIBUTING.md).

This is a small project; responses are best-effort.
```

- [ ] **Step 4: Commit**

```bash
git add CODE_OF_CONDUCT.md SECURITY.md SUPPORT.md
git commit -m "docs: add Code of Conduct, Security, and Support policies (#<ISSUE#>)"
```

---

## Task 3: CONTRIBUTING.md (workflow + branch-protection steps)

**Files:** Create `CONTRIBUTING.md`.

- [ ] **Step 1: Create `CONTRIBUTING.md`**

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs: add CONTRIBUTING guide with branch-protection steps (#<ISSUE#>)"
```

---

## Task 4: Issue & PR templates

**Files:** Create `.github/ISSUE_TEMPLATE/{bug_report.yml,feature_request.yml,chore.yml,config.yml}` and `.github/PULL_REQUEST_TEMPLATE.md`.

- [ ] **Step 1: Create `.github/ISSUE_TEMPLATE/config.yml`**

```yaml
blank_issues_enabled: false
contact_links:
  - name: Security vulnerability
    url: https://github.com/Zarlania/zarlania-api/security/advisories/new
    about: Report security issues privately — do not open a public issue.
```

- [ ] **Step 2: Create `.github/ISSUE_TEMPLATE/bug_report.yml`**

```yaml
name: Bug report
description: Something isn't working as expected.
labels: ["bug"]
body:
  - type: textarea
    id: what-happened
    attributes:
      label: What happened?
      description: A clear description of the bug, including steps to reproduce.
    validations:
      required: true
  - type: textarea
    id: expected
    attributes:
      label: Expected behavior
    validations:
      required: true
  - type: textarea
    id: context
    attributes:
      label: Environment / context
      description: Version, endpoint, request details, logs (redact secrets).
    validations:
      required: false
```

- [ ] **Step 3: Create `.github/ISSUE_TEMPLATE/feature_request.yml`**

```yaml
name: Feature request
description: Propose a new capability or change.
labels: ["enhancement"]
body:
  - type: textarea
    id: problem
    attributes:
      label: Problem / motivation
      description: What need does this address?
    validations:
      required: true
  - type: textarea
    id: proposal
    attributes:
      label: Proposed solution
    validations:
      required: true
  - type: checkboxes
    id: adr
    attributes:
      label: Architectural significance
      options:
        - label: This may need an ADR (see docs/adrs/0001-record-architecture-decisions.md)
```

- [ ] **Step 4: Create `.github/ISSUE_TEMPLATE/chore.yml`**

```yaml
name: Chore / maintenance
description: Tooling, dependencies, refactor, or housekeeping.
labels: ["chore"]
body:
  - type: textarea
    id: task
    attributes:
      label: Task
      description: What needs to be done and why.
    validations:
      required: true
```

- [ ] **Step 5: Create `.github/PULL_REQUEST_TEMPLATE.md`**

```markdown
## Summary

<!-- What does this PR do and why? -->

Closes #<!-- issue number -->

## Checklist

- [ ] This PR references its GitHub issue (in the title or branch name).
- [ ] No secrets are committed (keys/tokens/passwords).
- [ ] If this is an architecturally significant change, an ADR is included or linked.
- [ ] Tests and quality gates pass locally (`./scripts/check --full`).
```

- [ ] **Step 6: Commit**

```bash
git add .github/ISSUE_TEMPLATE/ .github/PULL_REQUEST_TEMPLATE.md
git commit -m "chore: add issue and pull request templates (#<ISSUE#>)"
```

---

## Task 5: Dependabot

**Files:** Create `.github/dependabot.yml`.

- [ ] **Step 1: Create `.github/dependabot.yml`**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: "/"
    schedule:
      interval: weekly
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: "/"
    schedule:
      interval: weekly
  - package-ecosystem: pip
    directory: "/"
    schedule:
      interval: weekly
  - package-ecosystem: docker
    directory: "/"
    schedule:
      interval: weekly
```

- [ ] **Step 2: Commit**

```bash
git add .github/dependabot.yml
git commit -m "chore: enable Dependabot for maven, actions, pip, and docker (#<ISSUE#>)"
```

> Note: Dependabot does not update `.pre-commit-config.yaml` hook revs; refresh those
> periodically with `pre-commit autoupdate` (a manual maintenance step).

---

## Task 6: CI workflow

**Files:** Create `.github/workflows/ci.yml`.

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [master]

permissions:
  contents: read
  pull-requests: write

jobs:
  build:
    name: Build & quality gates
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
          cache: maven
      - name: Maven verify (Spotless, Checkstyle, SpotBugs+FindSecBugs, tests, JaCoCo)
        run: ./mvnw -B clean verify
      - name: JaCoCo coverage report comment
        if: github.event_name == 'pull_request'
        uses: madrapps/jacoco-report@v1.7.1
        with:
          paths: ${{ github.workspace }}/target/site/jacoco/jacoco.xml
          token: ${{ secrets.GITHUB_TOKEN }}
          min-coverage-overall: 80
          min-coverage-changed-files: 80
          title: JaCoCo Coverage

  checks:
    name: Lint & ADR tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up Python
        uses: actions/setup-python@v5
        with:
          python-version: "3.12"
          cache: pip
      - name: Install dev dependencies
        run: pip install -r requirements-dev.txt
      - name: Cache pre-commit environments
        uses: actions/cache@v4
        with:
          path: ~/.cache/pre-commit
          key: pre-commit-${{ hashFiles('.pre-commit-config.yaml') }}
      - name: Run lint hooks (Java gates run in the build job)
        run: SKIP=java-quality pre-commit run --all-files --show-diff-on-failure
      - name: ADR tooling tests
        run: pytest --cov-fail-under=80

  secrets:
    name: Secret scan
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Install gitleaks
        run: |
          version=8.21.2
          curl -sSfL "https://github.com/gitleaks/gitleaks/releases/download/v${version}/gitleaks_${version}_linux_x64.tar.gz" \
            | tar -xz gitleaks
          sudo mv gitleaks /usr/local/bin/gitleaks
      - name: Scan PR commits for secrets
        run: |
          gitleaks git . --redact --no-banner \
            --log-opts="${{ github.event.pull_request.base.sha }}..${{ github.event.pull_request.head.sha }}"

  governance:
    name: PR references an issue
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - name: Verify issue reference in PR title or branch
        env:
          PR_TITLE: ${{ github.event.pull_request.title }}
          HEAD_REF: ${{ github.head_ref }}
        run: |
          if printf '%s' "$PR_TITLE" | grep -qE '#[0-9]+'; then
            echo "OK: PR title references an issue."
          elif printf '%s' "$HEAD_REF" | grep -qE '^[a-z]+/[0-9]+-'; then
            echo "OK: branch name references an issue."
          else
            echo "::error::PR must reference a GitHub issue — put #<issue> in the title or name the branch type/<issue#>-slug." >&2
            exit 1
          fi
```

- [ ] **Step 2: Validate the workflow YAML locally**

Run: `.venv/bin/pre-commit run check-yaml yamllint --files .github/workflows/ci.yml .github/dependabot.yml`
Expected: both PASS. Fix any yamllint issues (config is `.yamllint.yaml`).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions workflow (build, lint+ADR tests, secrets, governance) (#<ISSUE#>)"
```

---

## Task 7: Green the local fast suite over new files

**Files:** Possibly tune `.markdownlint.yaml`/`.yamllint.yaml` or fix new files.

- [ ] **Step 1: Run the fast suite**

Run: `./scripts/check`
Expected: all hooks pass over the new markdown/yaml/CODEOWNERS files. markdownlint and yamllint will lint the new docs and YAML. Fix any violations in the files; only tune `.markdownlint.yaml`/`.yamllint.yaml` narrowly if a rule is genuinely unreasonable.

- [ ] **Step 2: Commit any fixes**

```bash
git add -A
git commit -m "chore: satisfy linters for new governance files (#<ISSUE#>)"
```

---

## Task 8: Push, open PR, and verify CI is green (the real test)

- [ ] **Step 1: Push**

```bash
git push -u origin "feat/<ISSUE#>-ci-governance"
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create \
  --title "Phase 3: CI & governance (#<ISSUE#>)" \
  --body "$(cat <<'EOF'
Implements Phase 3 of the repo-shell spec (§4.3, §6, §9, §11).

## What's included
- **`.github/workflows/ci.yml`** — jobs: build (`./mvnw -B clean verify` on JDK 25 + JaCoCo
  coverage PR comment), checks (pre-commit lint hooks with `SKIP=java-quality` + ADR pytest),
  secrets (gitleaks over the PR commit range only), governance (PR must reference an issue).
- **CODEOWNERS** (`* @stimothy`), **issue/PR templates**, **Dependabot** (maven/actions/pip/docker),
  **CODE_OF_CONDUCT / SECURITY / SUPPORT**, and **CONTRIBUTING** (workflow + branch-protection steps).

## Deviations / scope
- **release.yml deferred to Phase 4** (depends on pom version source + bump-version + release labels).
- **Branch protection documented, not applied** (maintainer applies via GitHub settings; steps in CONTRIBUTING.md).
- **gitleaks-action not used** (paid for org repos); CI installs the binary and scans only PR commits.

Closes #<ISSUE#>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Watch CI and fix failures**

```bash
gh pr checks --watch
```
Expected: all four jobs succeed. If a job fails, read the logs (`gh run view --log-failed`), fix the workflow/config, commit, push, and re-watch. Common first-run issues to check: the gitleaks release asset name/URL, the JaCoCo XML path (`target/site/jacoco/jacoco.xml`), and pre-commit hook installation. Do not merge until CI is green.

- [ ] **Step 4: Confirm the JaCoCo coverage comment posted** on the PR (from the `build` job).

---

## Out of scope (later phases)

- App shell: actuator/springdoc/CORS, render.yaml, docker-compose, build-info version, `bump-version` (Phase 4).
- Release automation `release.yml` + in-PR version-bump/label validation (Phase 4 / ADR-0006).
- Seed ADRs 0002–0006 (Phase 5).
- Applying branch protection (maintainer, manual, post-merge).
