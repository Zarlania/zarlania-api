---
name: adr-create
description: Use when recording a new architecturally significant decision (new framework/major dependency, public API contract, persistence or auth model, build/deploy topology, cross-cutting convention, or repo-wide tooling).
---

# Creating an ADR

First confirm one is needed (see ADR-0001's trigger checklist) and that no ADR already
covers it (`./scripts/adr find "<topic>"`).

1. Pick tags. Reuse existing ones: `./scripts/adr tags`. If a new tag is truly needed,
   register it first: `./scripts/adr add-tag <tag> --description "..."`.
2. Create the ADR (status `proposed`): `./scripts/adr new --name "<imperative title>"
   --tags a,b --author stimothy`. **One subject per ADR.**
3. Fill in the MADR sections in the generated file. Do NOT hand-edit the frontmatter
   block or the meta table values inconsistently — keep frontmatter authoritative; if
   you change frontmatter, run `./scripts/adr index` and `./scripts/adr check`.
4. Leave status `proposed`. The user accepts it (`./scripts/adr accept <id>`); it is law
   only once accepted AND merged to master.
5. Regenerate the index and validate: `./scripts/adr index && ./scripts/adr check`.
