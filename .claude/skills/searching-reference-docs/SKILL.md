---
name: searching-reference-docs
description: Use to find what already exists in docs/references before reading files — dumps frontmatter or searches, so you spend tokens only on the docs you actually need.
---

# Searching reference docs

Lean on the tooling instead of reading whole files.

- Full frontmatter index (id, title, description, tags, related), no bodies:
  `cd docs/tooling && python references_cli.py meta`
- One doc's frontmatter: `python references_cli.py meta --id 000003`
- Search titles, descriptions, tags, and body text (prints id, title, filename,
  and a snippet): `python references_cli.py search "<query>"`

Open the file itself only once search/meta has told you which doc you need.
