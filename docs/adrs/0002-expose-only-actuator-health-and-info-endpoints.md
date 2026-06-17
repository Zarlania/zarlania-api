---
id: '0002'
name: Expose only Actuator health and info endpoints
description: Exposes only Actuator health and info over HTTP (details never) and surfaces
  the build version at /actuator/info.
status: accepted
date_proposed: '2026-06-16'
date_accepted: '2026-06-16'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- configuration
- security
---
# ADR-0002: Expose only Actuator health and info endpoints

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0002 |
| Name | Expose only Actuator health and info endpoints |
| Description | Exposes only Actuator health and info over HTTP (details never) and surfaces the build version at /actuator/info. |
| Status | accepted |
| Date proposed | 2026-06-16 |
| Date accepted | 2026-06-16 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | configuration, security |
<!-- adr-meta:end -->

## Context and Problem Statement

This is a live, public service hosted on Render, which needs a health endpoint to gate
deploys and route traffic. We also want minimal runtime observability — enough to see
liveness and the deployed version — without leaking internal details to anonymous
callers.

## Decision Drivers

- Render needs a health check endpoint.
- Observability must be minimal and non-leaky on a public service.
- Operators need to confirm which build version is running.

## Considered Options

- Expose only `health` and `info` (chosen).
- Expose a broader Actuator surface (metrics, env, beans, …).
- No Actuator; hand-roll a health endpoint.

## Decision Outcome

Chosen option: **depend on `spring-boot-starter-actuator` and expose only `health` and
`info` over HTTP**, because it gives Render a standard health signal and a version probe
with the smallest public surface.

1. `management.endpoints.web.exposure.include=health,info`; no other endpoints are web-exposed.
2. `management.endpoint.health.show-details=never` so health never leaks component internals.
3. Render's health check path is `/actuator/health`.
4. The Spring Boot `build-info` goal publishes the build version to `/actuator/info`
   (`build.version`), sourced from the pom version.

### Consequences

- Good: standard health signal for Render; version visibility; minimal attack surface.
- Bad: deeper diagnostics (metrics, env) require a deliberate, separately-secured change.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
