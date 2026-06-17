---
name: adr-tags
description: Use when choosing, reviewing, or adding ADR tags — to reuse existing tags before inventing new ones and keep the _tags.md registry consistent.
---

# ADR Tags

The tag registry is `docs/adrs/_tags.md`. Every tag used by any ADR must exist there.

- List tags + descriptions: `./scripts/adr tags`
- See usage counts: `./scripts/adr tag-usage`
- Find ADRs by tag: `./scripts/adr by-tag <tag>`
- Register a new tag (only if no existing tag fits): `./scripts/adr add-tag <tag>
  --description "..."`

Prefer reusing an existing tag. After changes, run `./scripts/adr check`.

Tags are kept in **alphabetical order** in `_tags.md` and in every ADR's `tags` list.
`add-tag` inserts new rows in order and `new` sorts the tags you pass; `check` fails if
either is out of order. When hand-editing an ADR's tags, keep them alphabetical.
