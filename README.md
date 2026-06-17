# Zarlania API

Backend service for Zarlania, deployed at <https://api.zarlania.com>. Java 25 / Spring
Boot / Maven, containerized and hosted on Render.

> Early scaffolding stage — see `docs/superpowers/specs/` for the design and
> `docs/superpowers/plans/` for implementation plans.

## Architecture Decision Records

Significant decisions are recorded as ADRs in `docs/adrs/`. Browse them with the CLI:

```bash
./scripts/adr list            # list all ADRs
./scripts/adr find "<query>"  # search ADRs
./scripts/adr show 0001       # show one ADR's metadata
./scripts/adr check           # validate ADRs (no drift, valid tags, fresh index)
```

The ADR process itself is defined in
`docs/adrs/0001-record-architecture-decisions.md`.

## Running locally

```bash
docker compose up --build      # serves on http://localhost:8080
```

Key endpoints: `GET /` (hello), `GET /actuator/health`, `GET /actuator/info` (version),
`GET /swagger-ui.html` (API docs), `GET /v3/api-docs` (OpenAPI JSON).

## Developer setup (Phase 1)

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest        # run ADR tooling tests
```
