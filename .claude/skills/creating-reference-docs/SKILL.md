---
name: creating-reference-docs
description: Use when documenting how part of zarlania works as a new reference doc in docs/references — scaffolds via the tooling, writes prose, and finalizes with the technical-writer agent.
---

# Creating a reference doc

Reference docs are living documentation of how the system works, for humans and
agents. They are NOT ADRs and NOT OpenAPI reference.

## Steps

1. Check for overlap first — do not duplicate an existing doc:
   `cd docs/tooling && python references_cli.py meta`
   and `python references_cli.py search "<topic>"`.
2. Register any new tag you need in `docs/references/_tags.md` before using it.
3. Scaffold (this allocates the next id, fills dates, and syncs):
   `python references_cli.py create --title "<Title>" --description "<one line>" --tags "tag1,tag2" --related "000003"`
4. Open the created file and write the prose body below the sister table. Explain
   behaviour and structure as they are today. Use ```mermaid blocks where a
   diagram is clearer than prose.
5. Re-sync and validate:
   `python references_cli.py sync && python references_cli.py validate`
   Fix any reported error before continuing.
6. **Finalize:** dispatch the `technical-writer` agent (Task tool, subagent_type
   `technical-writer`) to review the new doc for clarity and for repetition or
   contradiction against the rest of `docs/references`. Apply its edits, then run
   `validate` once more.
