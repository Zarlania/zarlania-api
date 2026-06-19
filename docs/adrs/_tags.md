# ADR Tags

Registry of all tags used across ADRs. **Reuse an existing tag before creating a new
one.** Adding a tag to an ADR requires adding it here in the same change
(`./scripts/adr add-tag <tag> --description "..."`).

Tags are kept in **alphabetical order** — both here and in each ADR's `tags` list.
`./scripts/adr add-tag` inserts new rows in order, and `./scripts/adr check` fails on
any registry or ADR whose tags are not sorted.

| Tag | Description |
| --- | --- |
| architecture | Architectural structure, boundaries, and cross-domain conventions |
| build | Build, runtime, and toolchain decisions |
| configuration | Runtime configuration and application properties |
| deployment | Deployment topology, hosting, and release/runtime infrastructure |
| documentation | Documentation practices, formats, and structure |
| governance | Repo governance, ownership, and enforcement |
| persistence | Persistence model, datasources, schema, and migrations |
| process | How we work; meta-process and workflow decisions |
| quality | Code quality, static analysis, and coverage gates |
| release | Versioning and release automation |
| security | Security model and controls (CORS, secrets, auth, exposure) |
