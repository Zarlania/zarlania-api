---
id: '0002'
name: Service runtime configuration and deployment topology
description: 'Defines Actuator exposure, public OpenAPI docs, the CORS allowlist model, and Render/Docker deployment topology.'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- deployment
- security
- configuration
---
# ADR-0002: Service runtime configuration and deployment topology

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0002 |
| Name | Service runtime configuration and deployment topology |
| Description | Defines Actuator exposure, public OpenAPI docs, the CORS allowlist model, and Render/Docker deployment topology. |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | deployment, security, configuration |
<!-- adr-meta:end -->

## Context and Problem Statement

The POC exposed a single endpoint with permissive CORS and no health/observability or
deploy-as-code. As the first production-shaped iteration we need: a health signal for
Render, machine-readable API docs, a safe CORS policy for the browser frontend, and the
deployment captured in the repo. This is a live, public service, so the runtime posture
must be secure by default.

## Decision Drivers

- Render needs a health check endpoint; we want minimal, non-leaky observability.
- The browser frontend (zarlania.com) must call the API without exposing it to all origins.
- API consumers need discoverable, accurate docs.
- Deployment should be reproducible and reviewed, not click-ops only.

## Considered Options

- Actuator `health`+`info` only vs. exposing more endpoints.
- CORS allowlist (config-driven) vs. permissive wildcard vs. a gateway.
- springdoc OpenAPI vs. hand-written docs vs. none.
- `render.yaml` Blueprint vs. dashboard-only configuration.

## Decision Outcome

1. **Actuator**: depend on `spring-boot-starter-actuator`; expose only `health` and `info`
   over HTTP (`management.endpoints.web.exposure.include=health,info`), with
   `health.show-details=never`. Render's health check path is `/actuator/health`.
2. **Version**: the Spring Boot `build-info` goal publishes the build version to
   `/actuator/info` (`build.version`), sourced from the pom version.
3. **API docs**: springdoc-openapi (3.x, Spring Boot 4 line) serves `/v3/api-docs` and a
   **public** `/swagger-ui.html`.
4. **CORS**: an explicit allowlist bound from `zarlania.cors.allowed-origins`
   (overridable via `ZARLANIA_CORS_ALLOWED_ORIGINS`), never a wildcard. Defaults to the
   production frontend origins; local dev adds localhost via env. This replaces the POC's
   permissive config and resolves the `PERMISSIVE_CORS` finding (issue #4).
5. **Deployment as code**: `render.yaml` codifies the Render web service (Docker runtime,
   free tier, health check, CORS env); `docker-compose.yml` runs the service locally; the
   `Dockerfile` runs as a non-root user with a version-agnostic jar copy.

### Consequences

- Good: secure-by-default CORS; reproducible deploy config; health + version visibility;
  discoverable docs.
- Bad: a public Swagger UI is extra surface (accepted for an OSS API); free-tier Render
  still cold-starts; CORS origins must be kept in sync with the frontend.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
- Issue #4 (CORS hardening)
