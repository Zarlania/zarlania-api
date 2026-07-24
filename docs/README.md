# docs

Documentation and doc tooling for `zarlania-api`.

| Directory | What it is | How to interact |
| --------- | ---------- | --------------- |
| `references/` | Living documentation of how the system works, for humans and agents. Numbered `NNNNNN-<slug>.md` files with frontmatter and a synced table. Kept current as code changes. | Use the tooling in `tooling/` or the reference-doc skills. Never hand-edit generated tables or the index. |
| `superpowers/` | Superpowers plugin plans (`plans/`) and specs (`specs/`). Historical snapshots of intent. | Left as written. After a PR opens, review comments on these are ignored; they are never backfilled to match later code. |
| `ai-prompts/` | Personal scratch space for AI prompts. | Drop markdown here; contents are gitignored (only the directory is tracked). |

ADRs are **not** here yet — a dedicated ADR layer, with its own tooling built on
the same library, arrives in a later session. Reference docs must not take on ADR
responsibilities (decisions, status, consequences) in the meantime.

## Reference doc workflow

The tooling in `tooling/` keeps token cost low — lean on it instead of reading
files. From `docs/tooling`:

| Command | Purpose |
| ------- | ------- |
| `python references_cli.py create --title T --description D --tags a,b --related 000003` | Scaffold a new doc (allocates id, fills dates, syncs). |
| `python references_cli.py sync` | Regenerate every sister table and the README index from frontmatter. |
| `python references_cli.py validate` | Structural check (ids, tags, sync, links). Runs in CI. |
| `python references_cli.py search "<query>"` | Search titles, descriptions, tags, and body text. |
| `python references_cli.py meta [--id NNNNNN]` | Dump frontmatter (no bodies) for a cheap overview. |

Frontmatter is the single source of truth; tables and the index are generated.
Every tag must be registered in `references/_tags.md`. After creating or updating
a doc, the reference-doc skills finalize by dispatching the `technical-writer`
agent to review prose and cross-doc consistency.
