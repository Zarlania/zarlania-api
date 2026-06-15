---
name: adr-search
description: Use FIRST when you need to find an existing ADR or check whether a decision already exists, before reading ADR files manually — saves tokens by querying the CLI.
---

# Searching ADRs

Use the CLI first for discovery instead of reading `docs/adrs/` files directly — it is far cheaper. Once `find`/`show` pinpoints the ADR id and you need the full prose, open that one file.

- List all ADRs: `./scripts/adr list` (filter: `--status accepted`, `--tag security`)
- Full-text search: `./scripts/adr find "<query>"`
- Inspect one ADR's metadata: `./scripts/adr show <id>` (e.g. `0001`)

Only open the full ADR file once `find`/`show` has pinpointed the right id and you need
the prose. To understand the ADR process itself, read ADR-0001.
