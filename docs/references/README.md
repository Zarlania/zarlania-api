# Reference documentation

Living documentation of how `zarlania-api` works, written so a human or an AI
agent can understand the system without reading all the code. Each entry is a
numbered markdown file with YAML frontmatter (the source of truth) and a synced
human-readable table.

**Do not hand-edit the index below or any sister table** — they are generated.
Use the tooling in `../tooling` (`references_cli.py create|sync|validate|search|meta`)
or the reference-doc skills. See `../README.md` for the full workflow.

These docs are **not** ADRs (a dedicated ADR layer arrives later) and **not**
OpenAPI reference (that is generated from Spring/springdoc, not written here).

## Index

<!-- reference-index:start -->
| ID | Title | Description | Tags |
| -- | ----- | ----------- | ---- |
| [000001](000001-persistence-foundation.md) | Persistence foundation | How the Postgres datasource, Flyway migrations, and JPA are configured and operated. | configuration, persistence |
| [000002](000002-authentication-and-tokens.md) | Authentication and tokens | How registration, email verification, login, JWT access tokens, refresh-token families, and the abuse defenses around them work end to end. | architecture, configuration, http, security |
| [000003](000003-outbound-email.md) | Outbound email | How the EmailSender port, its Resend and logging adapters, the service-wide send budget, and off-request-thread dispatch work. | architecture, configuration, security |
<!-- reference-index:end -->
