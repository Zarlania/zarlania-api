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

## Stack

Java 25 / Spring Boot 4.1.x / Maven (wrapper `./mvnw`) / multi-stage Temurin Docker.
For the version-adoption rationale see ADR-0006 (`./scripts/adr show 0006`).

## Code quality & structure

Keep this codebase clean as it grows from scaffolding into real features:

- Practice **DRY** and **SOLID**. Prefer small, single-responsibility units; refactor
  duplication instead of copying it.
- **Organize by feature/domain, not by flat technical-layer buckets.** Group related code
  together. As endpoints land, a domain like *users* should own its package (e.g.
  `com.zarlania.api.users` holding its controller/service/repository/entity) rather than a
  flat `controllers/` listing every controller and a flat `services/` listing every service.
  Group whenever there's a natural grouping.
- Cross-cutting / infrastructure code lives in its own package — e.g. configuration classes
  under a `config` package — not scattered at the application root.
- This is intent, not a rigid tree: use judgment, follow the established structure once it
  exists, and don't over-engineer (YAGNI).

## Releases (every merge ships)

Every merge to `master` cuts exactly one SemVer release. The version lives in `pom.xml`
`<version>` and is bumped **inside the PR** (never after merge — that would double-deploy).

When opening a PR:

1. Choose the bump from the change: breaking = `major`, feature = `minor`, fix/chore =
   `patch`. Apply the matching `release:<kind>` label (no label = `patch`).
2. Run `./scripts/bump-version bump <kind>` to set `pom.xml` to the next version.
3. CI's "Release version bump" check verifies the pom matches the label vs. the latest
   release tag; on merge, `release.yml` tags `v<version>` and cuts the GitHub Release.

## Working with ADRs (save tokens)

To find an ADR or check whether a decision exists, **use the `adr-search` skill / the
CLI — do not scan `docs/adrs/` by hand**:

- `./scripts/adr list` / `./scripts/adr find "<query>"` / `./scripts/adr show <id>`

To create one, use the `adr-create` skill. For tags, use `adr-tags`. Run
`./scripts/adr check` after any ADR change.

## Specs and plans are implementation-time only — not law

`docs/superpowers/` holds specs and plans. They guide a change **while it is being built**:
during implementation, and during the spec review of that same change, follow the spec/plan
relevant to the work in progress so nothing is missed. This is the normal flow
(brainstorm → spec → plan → implement → review the work against that plan's spec) and this
rule does not change it.

Once a change is merged to `master`, its spec and plan become **historical record only** —
not law, and not a standard to code against. The authoritative sources are the **ADRs and
the actual code**. Concretely:

- When implementing or reviewing change B, do **not** flag it for diverging from an earlier
  change A's spec or plan — those are frozen history. Judge B against the ADRs, the code,
  and B's own spec/plan.
- Dismiss any review comment that asks to edit a spec or plan file to match the code. Once
  merged they are not living documents. If a decision actually changed, that is a **new
  ADR**, not a spec edit.
