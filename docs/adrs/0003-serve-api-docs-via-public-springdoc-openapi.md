---
id: '0003'
name: Serve API docs via public springdoc OpenAPI
description: 'Serves a machine-readable OpenAPI document and a public Swagger UI via springdoc (Spring Boot 4 line).'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- documentation
- security
---
# ADR-0003: Serve API docs via public springdoc OpenAPI

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0003 |
| Name | Serve API docs via public springdoc OpenAPI |
| Description | Serves a machine-readable OpenAPI document and a public Swagger UI via springdoc (Spring Boot 4 line). |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | documentation, security |
<!-- adr-meta:end -->

## Context and Problem Statement

API consumers (including the browser frontend and external integrators) need accurate,
discoverable documentation. Hand-written docs drift from the code; we want docs generated
from the running application.

## Decision Drivers

- Docs must stay in sync with the actual endpoints.
- Consumers need both a machine-readable contract and a human-browsable UI.
- The dependency must support the Spring Boot 4 line.

## Considered Options

- springdoc-openapi (3.x, Boot 4 line) with a public Swagger UI (chosen).
- Hand-written OpenAPI/Markdown docs.
- No published docs.

## Decision Outcome

Chosen option: **springdoc-openapi (3.x)**, serving `/v3/api-docs` and a **public**
`/swagger-ui.html`, because it derives the contract from the code and gives consumers a
ready browsable UI. The UI is intentionally public for this OSS API.

### Consequences

- Good: always-accurate, discoverable docs; standard OpenAPI contract for tooling.
- Bad: a public Swagger UI is extra surface (accepted for an open API); springdoc must
  track the Spring Boot major line.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
