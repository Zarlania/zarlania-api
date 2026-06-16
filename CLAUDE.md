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

## Working with ADRs (save tokens)

To find an ADR or check whether a decision exists, **use the `adr-search` skill / the
CLI — do not scan `docs/adrs/` by hand**:

- `./scripts/adr list` / `./scripts/adr find "<query>"` / `./scripts/adr show <id>`

To create one, use the `adr-create` skill. For tags, use `adr-tags`. Run
`./scripts/adr check` after any ADR change.

## Status

Phases 1–4 are in place (ADRs, quality gates, CI/governance, and the app shell:
Actuator, OpenAPI, CORS allowlist, deploy config). Release automation and the seed
ADRs are still pending (see `docs/superpowers/`).
