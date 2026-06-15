---
id: '0001'
name: Record architecture decisions using MADR with frontmatter
description: 'Defines the ADR process: MADR format, dual frontmatter+table representation,
  lifecycle, one-subject rule, and tooling.'
status: accepted
date_proposed: '2026-06-15'
date_accepted: '2026-06-15'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- process
- documentation
- governance
---
# ADR-0001: Record architecture decisions using MADR with frontmatter

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0001 |
| Name | Record architecture decisions using MADR with frontmatter |
| Description | Defines the ADR process: MADR format, dual frontmatter+table representation, lifecycle, one-subject rule, and tooling. |
| Status | accepted |
| Date proposed | 2026-06-15 |
| Date accepted | 2026-06-15 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | process, documentation, governance |
<!-- adr-meta:end -->

## Context and Problem Statement

This is a live, public service where `master` deploys to production. We need durable,
reviewable records of architecturally significant decisions so the codebase cannot
silently drift from agreed direction, and so AI agents and humans share one source of
truth for "why".

## Decision Drivers

- Decisions must be machine-parseable (for AI agents) and human-readable.
- Records must not silently drift between machine and human representations.
- Changing one decision must not require invalidating unrelated ones.
- Finding/creating ADRs must be cheap (token-efficient) for AI agents.

## Considered Options

- MADR with YAML frontmatter + a mirrored human table (chosen).
- Nygard-style plain Markdown (no structured metadata).
- An external decision-log tool / database.

## Decision Outcome

Chosen option: **MADR with YAML frontmatter plus a rendered human metadata table**,
managed by the `./scripts/adr` CLI, because it satisfies both machine and human
readers and lets tooling enforce consistency.

Rules established by this ADR:

1. **Location & naming.** ADRs live in `docs/adrs/` as `NNNN-kebab-title.md`
   (zero-padded id). `_template.md` is the template; `_tags.md` is the tag registry;
   `README.md` is the generated index.
2. **Format.** MADR sections. YAML frontmatter is the **source of truth**; the table
   between `<!-- adr-meta:start -->`/`<!-- adr-meta:end -->` is rendered from it. The
   `check` command fails on any drift.
3. **Frontmatter schema.** `id, name, description, status, date_proposed,
   date_accepted, date_invalidated, author, supersedes, superseded_by, tags`.
4. **One subject per ADR.** Each ADR records exactly one decision, so a future change
   supersedes a single ADR rather than invalidating unrelated ones.
5. **Lifecycle.** `proposed → accepted → superseded | deprecated`, plus `rejected`.
   ADRs are created `proposed` and stay so until the user accepts them. **An ADR is
   authoritative only once `accepted` AND merged to `master`.**
6. **Sequencing (hybrid by risk).** Foundational/high-risk decisions land as their own
   ADR PR first; lower-risk ADRs travel with their implementing code.
7. **Required when** a change touches: a new framework/major dependency, a public API
   contract, the persistence/data model, the auth/security model, build/deploy
   topology, a cross-cutting convention, or repo-wide tooling.
8. **Tags.** Reuse an existing tag from `_tags.md` before creating a new one; new tags
   must be registered in `_tags.md` in the same change.
9. **The law.** Once accepted on `master`, code may not contradict an ADR without a new
   ADR that supersedes it (the old one flips to `superseded` with cross-links).
   This rule is enforced through code review and engineering discipline — not by
   `./scripts/adr check`, which only validates ADR metadata (schema, status, tag
   membership, filename/id match, ID sequence, and index freshness), not whether code
   contradicts a decision.
10. **Tooling.** All ADR operations go through `./scripts/adr` (see `--help`); the
    `adr-create`, `adr-search`, and `adr-tags` Claude skills wrap it.

### Consequences

- Good: single source of truth; enforced consistency; cheap lookups; atomic decisions.
- Bad: small ceremony per decision; contributors must run `./scripts/adr` rather than
  hand-editing metadata.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
- MADR: <https://adr.github.io/madr/>
