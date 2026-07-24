# Design: `docs/` directory and reference-docs tooling

- **Issue:** [#15](https://github.com/Zarlania/zarlania-api/issues/15)
- **Date:** 2026-07-23
- **Applies to:** `Zarlania/zarlania-api` (this repo) and `Zarlania/zarlania-app`,
  identically, except for one backend-only scope note (OpenAPI, see §2).

## Purpose

Establish a durable documentation structure and its guardrails while the repos
are still early scaffolding, so every later feature is documented consistently
and cheaply. Scripts do the heavy lifting to keep the token cost of authoring and
reading docs low, and the machinery is built to be reused by an ADR layer in a
future session.

## Directory layout

```text
docs/
  README.md                     Human entry point: what the 3 dirs are, how to use them
  ai-prompts/
    .gitkeep                    Only committed file; all other contents gitignored
  superpowers/                  Superpowers plugin default (plans/, specs/) — unchanged
  references/
    README.md                   Index table (id · title · description · tags), generated
    _template.md                Template for a new reference doc
    _tags.md                    Tag registry (tag · description) — source of truth for tags
    000001-<slug>.md            Reference docs, 6-digit zero-padded id prefix
  tooling/                      Shared Python package for doc tooling (ADR-reusable)
    pyproject.toml              ruff + mypy + pytest/coverage config (fail-under=80)
    docstooling/                Package: frontmatter, sister-table sync, index,
                                id-sequence, tag-validation, DocType config, CLI helpers
    references_cli.py           Thin CLI binding the shared lib to the reference doc-type
    tests/                      pytest suite, ≥80% coverage
```

### The three subdirectories

- **`ai-prompts/`** — a dumping ground for markdown prompts the maintainer feeds
  into AI sessions. The directory is committed via `.gitkeep`; every other file
  in it is gitignored. It is excluded from all linters.
- **`superpowers/`** — the Superpowers plugin's existing home for `plans/` and
  `specs/`. No structural change. Its documents are **historical snapshots**:
  after a PR opens, review comments on `docs/superpowers/plans/**` and
  `docs/superpowers/specs/**` are ignored — the code may legitimately diverge from
  them as it evolves. This does **not** restrict Superpowers' own reviews of a
  plan/spec *during* the implementation it drives. Snapshots are never backfilled
  to match later code. Excluded from all linters.
- **`references/`** — the living documentation of how the system works, written so
  either an AI agent or a human can understand the system. Reference docs are kept
  current as the code changes (unlike superpowers snapshots).

## Why a shared `docs/tooling/` package

ADRs will later reuse the same machinery — frontmatter plus a synced sister
table, `NNNNNN-<slug>` filenames, a generated README index, a template, and a tag
registry — with a different field set and document structure. The generic logic
therefore lives in one package parameterized by a `DocType` configuration object;
the future ADR CLI reuses it verbatim and supplies only its own fields, paths, and
template. Duplicating scripts under each doc directory was rejected: it violates
DRY exactly where duplication is known to be coming.

## Reference-doc format

**Frontmatter is the single source of truth.** The sister table and the README
index are *generated* from it and *validated* to be in sync, so a hand-edit that
skips `sync` fails CI.

```markdown
---
id: "000001"
title: Hello endpoint overview
description: How the hello-world endpoint is wired end to end.
tags: [http, controllers]
created: 2026-07-23
updated: 2026-07-23
related: ["000002"]
---

# Hello endpoint overview

<!-- reference-table:start -->
| Field | Value |
| ----- | ----- |
| ID | 000001 |
| Title | Hello endpoint overview |
| Description | How the hello-world endpoint is wired end to end. |
| Tags | http, controllers |
| Created | 2026-07-23 |
| Updated | 2026-07-23 |
| Related | [000002](000002-<slug>.md) |
<!-- reference-table:end -->

Prose documentation here…
```

- **Fields** (frontmatter and sister table both): `id, title, description, tags,
  created, updated, related`.
- **Filename:** `NNNNNN-<slug>.md`, 6-digit zero-padded id, kebab-case slug.
- **Mermaid:** reference docs may embed ` ```mermaid ` fenced blocks; GitHub
  renders them natively and markdownlint's MD040 is satisfied. The template ships
  a commented example.
- **Marker-delimited regions:** HTML comments (`<!-- reference-table:start -->` /
  `:end`) fence the regenerable sister table so `sync` never touches hand-written
  prose. The README index uses the same marker technique.
- **Not ADRs:** reference docs describe how the system works *today*. They carry
  no decision record, status, or consequences. The template and references README
  state this so reference docs do not drift into ADR responsibilities before the
  ADR layer exists.
- **Not OpenAPI (this repo only):** API/endpoint reference is generated from
  Spring/springdoc, not hand-written as reference docs. Documented as an explicit
  scope exclusion in CLAUDE.md and the references README. `zarlania-app` has no
  such note.

## Tooling commands

All commands are token-frugal — designed so Claude leans on them instead of
reading whole files.

| Command | Purpose |
| --- | --- |
| `create` | Scaffold a new reference doc from `_template.md`: allocate the next id, fill `created`/`updated`, write the file, run `sync`. Claude never hand-writes frontmatter. |
| `sync` | Regenerate every sister table and the README index from frontmatter. Idempotent. |
| `validate` | CI gate (non-zero exit on any failure): every doc ↔ README entry; every tag ∈ `_tags.md`; ids contiguous, unique, zero-padded, no gaps/dupes; frontmatter ↔ sister table ↔ README all in sync; required fields present; every `related` id exists. |
| `search` | Full-text/field query; prints compact results (id · title · path · snippet) so Claude reads matches, not whole files. |
| `meta` | Dump frontmatter for all docs (or one) — id, title, description, tags, related, created, updated — with **no body**. Gives Claude the whole index cheaply and up front. |

## Skills and agent (`.claude/`)

- `.claude/skills/creating-reference-docs/` — run `create`, write the prose,
  **finalize by invoking the `technical-writer` agent**.
- `.claude/skills/updating-reference-docs/` — locate via `search`/`meta`, edit,
  bump `updated`, run `sync`, **finalize via `technical-writer`**.
- `.claude/skills/searching-reference-docs/` — thin skill documenting `search`
  and `meta`.
- `.claude/agents/technical-writer.md` — a subagent that reviews Claude's
  reference prose, fixes poor writing, and checks the corpus for repetition and
  contradiction, editing docs directly to resolve. Deterministic structural rules
  stay in the scripts; the agent owns prose quality and cross-doc coherence. It
  runs at authoring time only (invoked by the create/update skills), never in CI.

Parallel ADR skills and an ADR-oriented agent are expected in a future session
and are out of scope here.

## CI, linters, gitignore

- **Lint workflow:** add a `docs` job (Python, no Java toolchain) that runs
  `references_cli validate`, `ruff format --check`, `ruff check`, `mypy`, and
  `pytest --cov --cov-fail-under=80` over `docs/tooling/`. Python tooling versions
  are pinned, consistent with how the repo pins its other linters.
- **markdownlint** (`.markdownlint-cli2.jsonc`): add `docs/superpowers/**` and
  `docs/ai-prompts/**` to `ignores`.
- **yamllint** (`.yamllint.yml`): ignore the same two paths.
- **.gitignore:** ignore `docs/ai-prompts/*` except `.gitkeep`.
- **Superpowers PR-review rule:** documented, not automated — reviews/comments on
  `docs/superpowers/plans/**` and `docs/superpowers/specs/**` are ignored after a
  PR opens.

## Documentation changes

- **CLAUDE.md:** new "Documentation (`docs/`)" section — the three directories,
  the reference-doc workflow (use the skills; scripts save tokens;
  `technical-writer` finalizes), the superpowers snapshot/ignore-reviews rule,
  ai-prompts as scratch, the OpenAPI and ADR scope exclusions, and a forward note
  that ADRs arrive later with their own (structurally similar) tooling.
- **README.md** (top-level) and **docs/README.md:** the human-facing version of
  the same.

## Testing

- `pytest` under `docs/tooling/tests/`, coverage enforced at ≥80% via
  `--cov --cov-fail-under=80` configured in `pyproject.toml`.
- Every script/command has tests: id allocation and sequence validation, tag
  registry enforcement, frontmatter ↔ sister-table ↔ README sync round-trips,
  `create`/`sync` idempotence, `search`/`meta` output shape, and the failure
  modes `validate` must catch (unknown tag, missing README entry, id gap/dupe,
  drifted table, missing `related`).

## Rollout

1. Land in `zarlania-api` first, following the repo workflow (issue → branch →
   PR, all three references matching, `Closes #15`, a size label).
2. Apply the identical structure to `zarlania-app` under its own tracking issue
   and branch. No further spec is written for the app repo; this spec governs both.

## Non-goals

- ADRs and any ADR-specific tooling (future session).
- Hand-written OpenAPI/API reference (generated by Spring/springdoc).
- Any change to Superpowers' internal workflows beyond documenting the
  snapshot/review convention.
- Cross-repo sharing of the tooling as a package or submodule; the structure is
  duplicated identically in each repo.
