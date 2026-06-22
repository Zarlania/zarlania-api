---
id: '0013'
name: Record explanatory reference docs as numbered living guides
description: Establishes docs/reference/ as a repo-wide system of living, editable
  explanations of how the system behaves, distinct from ADRs and implementation-time
  specs.
status: accepted
date_proposed: '2026-06-21'
date_accepted: '2026-06-21'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- documentation
- process
---
# ADR-0013: Record explanatory reference docs as numbered living guides

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0013 |
| Name | Record explanatory reference docs as numbered living guides |
| Description | Establishes docs/reference/ as a repo-wide system of living, editable explanations of how the system behaves, distinct from ADRs and implementation-time specs. |
| Status | accepted |
| Date proposed | 2026-06-21 |
| Date accepted | 2026-06-21 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | documentation, process |
<!-- adr-meta:end -->

## Context and Problem Statement

ADRs record *why* decisions were made — they are immutable once accepted and follow
a `proposed → accepted` lifecycle. Implementation-time specs and plans live in
`docs/superpowers/` and are historical record once their change is merged.
The `docs/ai-prompts/` scratchpad is git-ignored and never authoritative.

None of these locations is the right home for living explanations of *how the system
behaves*: descriptions of domain rules, API contracts, configuration semantics, or
cross-cutting behaviour that must stay current as the code evolves. Without a
designated place and conventions for such material, explanations scatter across
comments, READMEs, and chat history and quickly go stale.

## Decision Drivers

- The codebase needs a single, findable location for explanations of current system
  behaviour that is distinct from decision records.
- Reference content must be updatable in place — no lifecycle ceremony, no "superseded"
  states — because the behaviour it describes changes over time.
- Tooling must be consistent with the ADR system (shared core module, pre-commit
  validation, generated index) to avoid two divergent maintenance burdens.
- Authoring must be cheap: no new skills required at launch (YAGNI); the CLI and a
  CLAUDE.md pointer are sufficient.

## Considered Options

- **`docs/reference/` with its own numbered scheme, tag registry, generated index,
  and `./scripts/ref` CLI** (chosen) — mirrors the ADR system structurally but
  carries no lifecycle.
- **Inline comments and per-domain READMEs only** — discoverable by developers reading
  that file, but scattered and not indexed; hard for agents to query systematically.
- **Extend ADRs to cover explanatory content** — misuses the ADR format; mixes
  immutable decisions with mutable descriptions; pollutes the decision record.

## Decision Outcome

Chosen option: **establish `docs/reference/` as a repo-wide reference-docs system**,
because it provides a dedicated, indexed, and consistently tooled home for living
explanations without conflating them with decision records or implementation-time
artefacts.

The system is defined as follows:

1. **Location & naming.** Reference docs live in `docs/reference/` as
   `NNNNNN-kebab-title.md` (six-digit zero-padded id). `_tags.md` is the tag
   registry; `README.md` is the generated index.
2. **No lifecycle.** Reference docs have no `proposed → accepted` lifecycle. They are
   created current and updated in place whenever the behaviour they describe changes.
3. **Scope.** A reference doc explains *how the system behaves* — domain rules, API
   semantics, configuration, cross-cutting conventions. It is not a decision record,
   not a spec, and not a tutorial.
4. **Shared core.** The `./scripts/ref` CLI is built on the same `core` module used
   by `./scripts/adr`, keeping tag handling, index generation, and validation logic
   DRY.
5. **Validation.** `./scripts/ref check` is wired into pre-commit alongside
   `./scripts/adr check`, so both systems stay consistent on every commit.
6. **Authoring skills deferred.** No `ref-create` or `ref-search` Claude skills are
   introduced at launch. The CLI (`./scripts/ref new`, `./scripts/ref list`,
   `./scripts/ref find`, `./scripts/ref show`) plus a CLAUDE.md pointer are sufficient
   (YAGNI).
7. **Distinct from other doc types.** Reference docs are not ADRs (no lifecycle, not
   immutable decisions), not superpowers specs/plans (not implementation-time only),
   and not the `docs/ai-prompts/` scratchpad (committed, authoritative, indexed).

### Consequences

- Good: single authoritative location for living behavioural explanations; consistent
  tooling with the ADR system via shared core; pre-commit validation prevents stale
  indexes and unknown tags; agents can query the index cheaply.
- Good: additive — supersedes no existing ADR; existing doc locations are unchanged.
- Bad: contributors must run `./scripts/ref` for reference-doc operations rather than
  editing files directly (same ceremony as the ADR system).

## Links

- Spec: `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md`
- ADR-0001: `docs/adrs/0001-record-architecture-decisions.md` — the parallel system
  for immutable decision records
