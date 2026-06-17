# Spec Review — Accepted Divergences

Transient working registry for the repo-shell finalization review (issue #31). It
records every **accepted** divergence between the approved spec
(`docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`) and the shipped
implementation. The permanent record of each divergence is the ADR it points to; this
file feeds the PR description and is removed before merge (it never lands on `master`).

## Accepted divergences

| Spec reference | What shipped | Why it's accepted | Governing ADR |
| --- | --- | --- | --- |
| Stack table (§32): Spring Boot 3.5.x / springdoc ~2.8.x | Spring Boot 4.1.0 / springdoc 3.0.3 | The spec's stated policy is "pick the latest versions compatible at implementation time" to minimize first-Dependabot churn; 4.1.0/3.0.3 are the latest-compatible at build time. | ADR-0006 |
| §3.1: ten separate scripts under `scripts/adr/` | One `./scripts/adr` CLI over a tested `adr_tool` Python package (subcommands `new`, `list`, `find`, `show`, `tags`, `add-tag`, `tag-usage`, `by-tag`, `accept`, `index`, `check`) | A single CLI exposes the same operations with less duplication and is unit-tested; every spec'd operation is still available. | ADR-0007 |
| §4.2 / §4.3: bash scripts tested with bats + kcov | ADR/release tooling written in Python, tested with pytest + coverage (≥ 80% line + branch); no bats/kcov | Tooling is Python, so Python-native testing applies; the same coverage bar is enforced. | ADR-0007 |
| §4.2: CI gitleaks "full scan" | CI gitleaks scans the PR commit range (`base..head`); pre-commit scans staged changes | Repository was created with clean history, so a full-history scan adds no value; new secrets are blocked at staging (pre-commit) and at the PR-commit-range layer in CI. | ADR-0007 |
| §1: CORS default includes localhost dev origins | App built-in default is the production origins only (`https://zarlania.com`, `https://www.zarlania.com`); localhost dev origins are supplied per environment via `ZARLANIA_CORS_ALLOWED_ORIGINS` (e.g. in `docker-compose.yml`) | A production-safe default avoids accidentally shipping localhost origins to prod; dev origins are injected by environment instead. | ADR-0004 (note) |

## Deferred (tracked elsewhere)

- Render Blueprint migration (spec §7): connecting the existing Render service to
  `render.yaml` as a Blueprint is tracked in GitHub issue #27. The `render.yaml`
  Blueprint file itself is present in the repo; only the dashboard migration is deferred.

## Tracked, not yet done

None. The full spec walk (§1–§15) found no unrecorded gaps; every divergence above is
intentional and governed by an accepted ADR.
