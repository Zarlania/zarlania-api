---
name: updating-reference-docs
description: Use when the system changed and an existing docs/references doc must be brought up to date — locates the doc, edits it, re-syncs, and finalizes with the technical-writer agent.
---

# Updating a reference doc

Reference docs track the current system, so update them as code changes. (Never
retro-edit docs/superpowers plans/specs — those are historical snapshots.)

## Steps

1. Locate the doc:
   `cd docs/tooling && python references_cli.py search "<topic>"`
   or list everything with `cd docs/tooling && python references_cli.py meta`.
2. Edit the prose and, if fields changed, the YAML frontmatter. Set `updated:`
   to today's date. Keep `tags` alphabetical and **reuse existing tags from
   `docs/references/_tags.md`** — only add a new row (kept alphabetical) when no
   existing tag covers the change. Make sure `description` and `tags` still
   reflect what the doc now says.
3. Re-sync and validate (regenerates the sister table and README index):
   `cd docs/tooling && python references_cli.py sync && python references_cli.py validate`
   Fix any reported error.
4. **Finalize:** dispatch the `technical-writer` agent to review the change for
   clarity and cross-doc consistency. Apply its edits, then run `validate` again.
