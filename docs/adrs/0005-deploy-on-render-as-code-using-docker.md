---
id: '0005'
name: Deploy on Render as code using Docker
description: 'Codifies the Render web service in render.yaml with a non-root Docker image and docker-compose for local dev.'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- configuration
- deployment
---
# ADR-0005: Deploy on Render as code using Docker

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0005 |
| Name | Deploy on Render as code using Docker |
| Description | Codifies the Render web service in render.yaml with a non-root Docker image and docker-compose for local dev. |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | configuration, deployment |
<!-- adr-meta:end -->

## Context and Problem Statement

The POC was configured by hand in the Render dashboard, leaving the deployment undocumented
and unreviewable. As the first production-shaped iteration we want the deployment captured
in the repo and reproducible locally.

## Decision Drivers

- Deployment should be reviewed, not click-ops only.
- Local development should run the service the same way it runs in production.
- The runtime must be secure by default (non-root container).

## Considered Options

- `render.yaml` Blueprint (deploy-as-code) with Docker runtime (chosen).
- Dashboard-only configuration.
- A different host/PaaS.

## Decision Outcome

Chosen option: **codify the Render web service in `render.yaml`** (Docker runtime, free
tier, health check `/actuator/health`, CORS origins as an env var). The `Dockerfile` runs
as a non-root user with a version-agnostic jar copy, and `docker-compose.yml` runs the
service locally against the same image.

### Consequences

- Good: reproducible, reviewed deploy config; local/prod parity; non-root runtime.
- Bad: free-tier Render still cold-starts; some dashboard-only settings (e.g. the
  health-check path, secret env values) must still be applied out of band.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
