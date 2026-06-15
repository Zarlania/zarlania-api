# Zarlania API — Official Repo Shell & Governance Design

**Date:** 2026-06-15
**Status:** Approved design (pending user review of this spec)
**Supersedes operationally:** the 2026-06-14 Hello-World POC (POC code is scrapped; deployment topology is retained)

## Goal

Replace the throwaway POC with the *official* foundation for the Zarlania backend: a
minimal but production-grade Spring Boot service plus the full governance, quality,
security, documentation, and release machinery that every future change must flow
through. The application itself stays intentionally minimal for now (health/actuator
only) — the value of this effort is the **conventions and automation**, because
`master` deploys straight to the live public service at `https://api.zarlania.com`.

This is a **public, open-source-capable repository**. Everything visible must be safe
for public consumption, and contributor-facing health files must exist.

## Success Criteria

1. A new contributor (human or AI) can read `CLAUDE.md` / `README.md` and understand
   how to make a compliant change end to end.
2. `./mvnw verify` runs format, lint, static analysis, security analysis, and coverage
   gates locally and in CI.
3. Pre-commit catches fast issues (format, lint, secrets) before a commit lands.
4. Every architecturally significant decision is recorded as an ADR; tooling/skills
   make ADRs cheap to create, find, and validate, and a drift check keeps them honest.
5. Merging a PR to `master` deploys once to prod and (for releasing PRs) produces a
   SemVer tag + GitHub Release automatically — no manual release steps, no double deploy.
6. No secret ever lands in a commit (gitleaks on pre-commit **and** CI).

## Stack & Versions (carried from POC, toolchain-verified)

| Concern | Choice | Notes |
|---|---|---|
| Language/runtime | **Java 25 (LTS)** | Verified supported by SpotBugs 4.9.8+ and FindSecBugs 1.14.0 (plugin). |
| Framework | **Spring Boot 3.5.x** | Pin latest patch at implementation time. |
| Build | **Maven** (wrapper `./mvnw`) | Quality gates are Maven-driven. |
| Container | Multi-stage **Dockerfile** (Temurin 25 jdk → jre) | Build source for Render. |
| API docs | **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui` ~2.8.x) | Auto-generates OpenAPI + Swagger UI from controllers. (Spring Boot does not ship Swagger UI itself; springdoc is the standard.) Swagger UI `/swagger-ui.html`, spec `/v3/api-docs`. |

Dependency policy: pick the **latest versions compatible** with the above at
implementation time, to minimize the first Dependabot churn after release.

## 1. Application Shell (minimal)

- `spring-boot-starter-actuator`. Expose **only `health` + `info`** over HTTP
  (`management.endpoints.web.exposure.include=health,info`); everything else
  (env, beans, heapdump, mappings, …) stays disabled — public prod service.
- Liveness/readiness health groups enabled. Render health check → `/actuator/health`.
- `/actuator/info` surfaces the build **version** (via `spring-boot-maven-plugin`
  `build-info`, sourced from `pom.xml <version>`).
- `springdoc` for API docs (see stack table).
- **CORS**: remove the POC's permissive config. Configure an **env-driven allowlist**
  (default: `https://zarlania.com`, `https://www.zarlania.com`, plus `localhost`
  origins for dev) via a typed config property bound from environment/properties.
  Security-significant → covered by an ADR.

## 2. ADR System

ADRs are the **law** of this repo: once an ADR is Accepted on `master`, code may not
contradict it without a new ADR that **supersedes** it.

### 2.1 Layout (`docs/adrs/`)

| File | Purpose |
|---|---|
| `NNNN-kebab-title.md` | One ADR. Zero-padded id (`0001`, `0002`, …). |
| `_template.md` | The canonical template every ADR is created from. |
| `_tags.md` | Central registry of all valid tags (tag → description). |
| `README.md` | Human index of ADRs (generated/maintained: id, name, status, tags). |

### 2.2 One subject per ADR

Each ADR captures **exactly one decision/subject**. This keeps decisions atomic: when
one decision later changes, we supersede a single ADR rather than invalidating unrelated
decisions that happened to share a file.

### 2.3 Dual representation (machine + human) with drift protection

Every ADR carries the **same metadata twice**:

1. **YAML frontmatter** — the machine-parseable source of truth (for AI + scripts).
2. A **Markdown table** in the body mirroring the frontmatter — readable when
   frontmatter isn't rendered (e.g., raw GitHub view).

A script validates the two never drift (frontmatter ↔ table field-by-field). The drift
check runs in **pre-commit and CI**.

### 2.4 Frontmatter schema

```yaml
---
id: 0007                      # zero-padded, matches filename
name: Use Postgres for persistence
description: One-line summary of the decision
status: proposed              # proposed | accepted | superseded | deprecated | rejected
date_proposed: 2026-06-20
date_accepted:                # set when accepted (on master)
date_invalidated:             # set when superseded/deprecated/rejected
author: stimothy
supersedes:                   # ADR id(s) this replaces, e.g. [0003]
superseded_by:                # ADR id(s) that replaced this, e.g. [0011]
tags: [persistence, database] # all must exist in _tags.md
---
```

The body uses the **MADR** structure: Title, Status, Context & Problem, Decision
Drivers, Considered Options, Decision Outcome, Consequences (good/bad), Links — plus
the human metadata table mirroring the frontmatter.

### 2.5 Lifecycle & acceptance semantics

- New ADRs are created as **`proposed`** (`date_proposed` set, `author` set).
- An ADR stays `proposed` until **the user explicitly accepts it**. Acceptance flips
  `status: accepted` and sets `date_accepted`.
- **An ADR is only authoritative once it is `accepted` AND merged to `master`.** A
  PR-local `accepted` ADR is not yet law until the merge lands.
- Status transitions: `proposed → accepted`; `accepted → superseded` (with
  `superseded_by`/`supersedes` cross-links and `date_invalidated`); `accepted →
  deprecated` (no replacement); `proposed → rejected` (declined; kept for history).

### 2.6 Sequencing (hybrid by risk)

- Foundational / high-risk decisions: the ADR lands first as its **own PR**, accepted
  on `master`, before the implementing code references it.
- Lower-risk decisions: the ADR travels in the **same PR** as the implementing code.

### 2.7 Required when (the "architecturally significant" trigger checklist)

An ADR is required when a change touches any of: a new framework or major dependency · a
public API contract · the persistence/data model · the auth/security model · the
build/deploy topology · a cross-cutting convention · repo-wide tooling. ADR-0001 codifies
this list and the whole process.

### 2.8 Tag governance

- `_tags.md` is the single registry of allowed tags. Before inventing a tag, **check
  `_tags.md` for an existing tag that fits** (the ADR-tags skill assists).
- Adding a new tag to an ADR **requires** adding it to `_tags.md` in the same change.
- CI/pre-commit validates: every tag used by any ADR exists in `_tags.md`.

## 3. ADR Tooling — Scripts + Claude Skills

Machine-repeatable ADR operations are implemented as **tested** bash/python scripts so
AI agents spend tokens on judgment, not mechanics. Claude **skills** wrap these scripts
with usage guidance.

### 3.1 Scripts (`scripts/adr/`)

| Script | Does |
|---|---|
| `new-adr` | Scaffold the next-numbered ADR from `_template.md` (sets id, `date_proposed`, author, `status: proposed`, synced frontmatter + table). |
| `list-adrs` | List all ADRs (id, name, status, tags). |
| `find-adrs` | Full-text search across ADR frontmatter + body; prints matching ids/paths. |
| `show-adr` | Print an ADR's frontmatter (and optionally the body). |
| `list-tags` | List all tags from `_tags.md` with descriptions. |
| `add-tag` | Add a new tag (+ description) to `_tags.md`. |
| `tag-usage` | Show usage counts per tag (which ADRs use each). |
| `adrs-by-tag` | List ADRs carrying a given tag. |
| `accept-adr` | Flip `proposed → accepted`, set `date_accepted` (frontmatter + table). |
| `check-adrs` | Validate: frontmatter↔table sync, schema completeness, tag registry consistency, id/filename match. (Runs in pre-commit + CI.) |

### 3.2 Claude skills (`.claude/skills/`)

| Skill | Wraps | Purpose |
|---|---|---|
| `adr-create` | `new-adr` (+ guidance) | Create a one-subject ADR; reuse existing tags; fill MADR sections. |
| `adr-search` | `find-adrs`, `show-adr`, `list-adrs` | Token-efficient lookup: "does an ADR exist for X?", "what did we decide about Y?" — query via scripts instead of reading files. |
| `adr-tags` | `list-tags`, `tag-usage`, `adrs-by-tag`, `add-tag` | Inspect/reuse/add tags responsibly. |

`CLAUDE.md` explicitly points agents at the `adr-search` skill/scripts as the **first**
move when they need to find something in an ADR or check whether one exists — to save
tokens versus scanning the docs.

## 4. Quality & Security Gates

### 4.1 Maven-driven (run in `./mvnw verify`)

| Tool | Plugin (approx) | Config |
|---|---|---|
| Format | `spotless-maven-plugin` ~2.44.x | **google-java-format**; `check` fails build, `apply` fixes. |
| Style | `maven-checkstyle-plugin` ~3.6.x + Checkstyle 10.x | Google `google_checks.xml` (tuned to coexist with google-java-format). |
| Bugs | `spotbugs-maven-plugin` ~4.9.x | effort = Max. |
| Security | `findsecbugs-plugin` 1.14.0 (SpotBugs plugin) | runs with SpotBugs. |
| Coverage | `jacoco-maven-plugin` ~0.8.13+ | **line ≥ 80% AND branch ≥ 80%** (tunable); excludes app main class / config / generated. Build fails under threshold. |
| Build info | `spring-boot-maven-plugin` | `build-info` → version to `/actuator/info`. |

### 4.2 Pre-commit vs CI split (deliberate)

Your brief listed SpotBugs/FindSecBugs/JaCoCo for both pre-commit and CI but also said
"only fast things in pre-commit, no tests." Those three (plus tests) require compilation
and are slow, so they are **CI-only**. Pre-commit gets the fast checks.

**Pre-commit** (`pre-commit` framework, `.pre-commit-config.yaml`):
Spotless format check · Checkstyle · **gitleaks** · markdownlint · shellcheck + shfmt ·
ruff (python lint+format) · yaml-lint · `check-adrs` · hygiene hooks
(trailing-whitespace, end-of-file, large-file, merge-conflict, detect-private-key).

**CI** (GitHub Actions) runs everything in pre-commit **plus**: full `./mvnw verify`
(SpotBugs/FindSecBugs/JaCoCo/tests), pytest (+coverage) for python scripts, bats
(+coverage) for bash scripts, gitleaks full scan, ADR checks, and PR governance.

### 4.3 Coverage across languages + PR reporting

- **Java**: JaCoCo line + branch ≥ 80%.
- **Python** (scripts): pytest + `coverage`/`pytest-cov`, line + branch ≥ 80%
  (configurable), applied only where python scripts exist.
- **Bash** (scripts): bats tests with coverage via `kcov` (best-effort threshold);
  applied only where bash scripts exist.
- CI **posts a coverage summary comment to the PR** (e.g. `madrapps/jacoco-report` for
  Java plus a coverage-comment action for python/bash), so coverage is visible inline.

## 5. Contribution Workflow (enforced)

- **Issue-driven**: every change has a GitHub issue.
- **Branch naming**: `type/<issue#>-slug` (e.g. `feat/12-actuator-shell`,
  `fix/34-cors-origin`). Types align with issue templates.
- **PR title** references the issue (`#<issue>`).
- **CI governance check** verifies the PR title/branch references an issue number and
  carries a valid release label (see §6).
- **Templates**: `.github/ISSUE_TEMPLATE/` (bug, feature, chore) + `PULL_REQUEST_TEMPLATE.md`
  (issue-ref checkbox, release-label reminder, ADR checklist, secrets checklist).
- **CODEOWNERS**: `* @stimothy`.
- **Branch protection** on `master` (documented manual GitHub setup; exact steps in
  README/CONTRIBUTING): require PR, require passing status checks, require 1 codeowner
  approval, disallow direct pushes.

## 6. Release & Versioning (fully automated, single deploy)

- **Source of truth**: `pom.xml <version>` (SemVer). Surfaced at `/actuator/info`.
- **Per-PR bump (in-PR)**: every merge produces a release. A PR labeled
  `release:major|minor|patch` sets the bump; **unlabeled PRs default to `patch`**. The
  version bump is committed **inside the PR** (so the merge is the only push → Render
  deploys exactly once; no post-merge commit).
- **Claude understands the scheme**: when building a PR, Claude applies the correct
  label and bumps `pom.xml <version>` accordingly (helper: `scripts/bump-version`).
- **CI validation (on PR)**: a check confirms the in-PR `pom.xml` version matches the
  release label relative to the latest release tag (rejects mismatches / missing bump).
- **On merge to `master`** (`release.yml`): create the matching git **tag** and a
  **GitHub Release** with auto-generated notes (the changelog-equivalent — no maintained
  `CHANGELOG.md` file). No new commit is produced.

## 7. Deployment

- **`render.yaml`** Blueprint (infrastructure-as-code, in repo): defines the Render web
  service (Docker runtime, free tier, health check path `/actuator/health`, env var
  wiring, region). Codifies "Render as the production convention."
- **`docker-compose.yml`** for **local dev** (app now; DB/other services later). Render
  does **not** build from compose — the Dockerfile remains the build source.
- **CD**: push/merge to `master` → Render auto-deploys to prod (`api.zarlania.com`).
- Migration steps for connecting the existing Render service to `render.yaml` (Blueprint)
  will be documented; provided as guided dashboard instructions.

## 8. Repository Layout (target)

```
.
├── .github/
│   ├── workflows/            # ci.yml, release.yml
│   ├── ISSUE_TEMPLATE/       # bug, feature, chore
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── dependabot.yml
├── .claude/skills/           # adr-create, adr-search, adr-tags
├── .pre-commit-config.yaml
├── CODEOWNERS
├── CLAUDE.md                 # AI entry point (ADR law, workflow, commands, secrets policy, adr-search)
├── README.md                 # human entry point (setup, commands)
├── CONTRIBUTING.md
├── CODE_OF_CONDUCT.md        # Contributor Covenant 2.1
├── SECURITY.md               # GitHub private advisories + steven.timothy265@gmail.com
├── SUPPORT.md
├── LICENSE                   # MIT (existing, © Zarlania)
├── render.yaml
├── docker-compose.yml
├── Dockerfile
├── pom.xml
├── docs/
│   ├── adrs/                 # _template.md, _tags.md, README.md, NNNN-*.md
│   └── ...                   # other non-ADR, non-API docs (NOT API docs — springdoc owns those)
├── scripts/
│   ├── adr/                  # new-adr, list-adrs, find-adrs, show-adr, list-tags,
│   │                         #   add-tag, tag-usage, adrs-by-tag, accept-adr, check-adrs
│   ├── setup-dev             # install pre-commit + hooks + deps
│   ├── check                 # run fast local checks (mirror of pre-commit)
│   ├── bump-version          # set pom version per release bump type
│   └── tests/                # bats + pytest for the scripts
└── src/                      # minimal Spring Boot app (actuator, springdoc, CORS, config)
```

## 9. Open-Source Health Files

`CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md` (GitHub Private
Vulnerability Reporting as primary + `steven.timothy265@gmail.com` fallback),
`SUPPORT.md` (where to get help), plus `CONTRIBUTING.md`, `CODEOWNERS`, issue/PR
templates already covered. (README status badges: declined for now.)

## 10. Secrets Policy

- **No secret ever lands in a commit.** Enforced by gitleaks (pre-commit **and** CI) +
  `detect-private-key` hook.
- Secrets live only in Render environment variables (and local `.env`, git-ignored).
- `CLAUDE.md` and `CONTRIBUTING.md` call this out prominently.
- `.gitignore` excludes env files and local secret material.

## 11. Dependabot

`dependabot.yml` ecosystems: Maven, GitHub Actions, Docker, and pip (python scripts),
weekly. (Pre-commit hook versions are refreshed via `pre-commit autoupdate`, run as a
periodic maintenance step / CI job, since Dependabot doesn't natively update
`.pre-commit-config.yaml`.)

## 12. Seed ADRs (dogfoods the process)

1. **ADR-0001** — The ADR process itself (this design becomes the law).
2. **ADR-0002** — Language, runtime & build (Java 25 / Spring Boot 3.5 / Maven / Docker).
3. **ADR-0003** — Code quality & security gates (toolchain, thresholds, pre-commit↔CI split).
4. **ADR-0004** — Contribution workflow (issue-driven, branch/PR naming, enforcement, CODEOWNERS, branch protection).
5. **ADR-0005** — Deployment topology & runtime config (Render free tier, render.yaml,
   docker-compose, actuator exposure, CORS allowlist) and release/versioning model.

## 13. Bootstrap Ordering (phased implementation)

The shell necessarily lands before all gates are live; the first PRs establish the
machinery, then everything afterward follows the full process.

1. **Phase 1 — ADR foundation**: `docs/adrs/` (`_template.md`, `_tags.md`, `README.md`),
   ADR-0001, ADR scripts + `check-adrs`, the three Claude skills, CLAUDE.md/README skeletons.
2. **Phase 2 — Quality tooling**: pom plugins (spotless/checkstyle/spotbugs/findsecbugs/
   jacoco), `.pre-commit-config.yaml`, `scripts/setup-dev`, `scripts/check`, script tests.
3. **Phase 3 — CI & governance**: `ci.yml`, `release.yml`, CODEOWNERS, issue/PR templates,
   `dependabot.yml`, OSS health files, branch-protection docs.
4. **Phase 4 — App shell & deploy**: actuator + springdoc + CORS + version/build-info,
   `render.yaml`, `docker-compose.yml`, Dockerfile update, `scripts/bump-version`.
5. **Phase 5 — Seed ADRs 0002–0005** documenting the above.

## 14. Resolved Decisions

| Decision | Choice |
|---|---|
| ADR format | MADR + YAML frontmatter + mirrored human table (drift-checked) |
| ADR statuses | proposed → accepted → superseded/deprecated (+ rejected) |
| ADR trigger | defined trigger checklist (§2.7) |
| ADR sequencing | hybrid by risk |
| ADR atomicity | one subject per ADR |
| ADR acceptance | proposed until user accepts; authoritative only when accepted **and** on master |
| Changelog | none (GitHub Release auto-notes instead) |
| Versioning | SemVer in `pom.xml`, automated tag + Release on merge |
| Release trigger | every merge releases; label `release:*`, unlabeled = patch; bump in-PR |
| Deploy config | `render.yaml` (prod) + `docker-compose` (local) |
| Issue gating | enforced in CI + templates |
| Coverage | line + branch ≥ 80% (Java/python/bash), posted to PR |
| License | MIT (existing) |
| Security contact | GitHub private advisories + steven.timothy265@gmail.com |
| OSS files | CODE_OF_CONDUCT.md, SECURITY.md, SUPPORT.md |

## 15. Out of Scope (future)

- Business logic, persistence, authentication (each will arrive via its own issue + ADR).
- Always-on hosting (free tier sleeps ~15 min; upgrade later).
- Staging environment / multi-region.
- OWASP dependency-check (Dependabot + FindSecBugs cover the initial need).
- README status badges.
