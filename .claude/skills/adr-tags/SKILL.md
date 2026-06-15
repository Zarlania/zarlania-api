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
