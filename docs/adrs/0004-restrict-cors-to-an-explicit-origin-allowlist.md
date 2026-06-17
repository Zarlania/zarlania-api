---
id: '0004'
name: Restrict CORS to an explicit origin allowlist
description: Binds CORS origins from configuration as an explicit allowlist (never
  a wildcard), replacing the POC permissive config.
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
# ADR-0004: Restrict CORS to an explicit origin allowlist

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0004 |
| Name | Restrict CORS to an explicit origin allowlist |
| Description | Binds CORS origins from configuration as an explicit allowlist (never a wildcard), replacing the POC permissive config. |
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

The browser frontend (zarlania.com) must call this API cross-origin, but the POC shipped a
permissive CORS configuration that exposed the API to all origins (the `PERMISSIVE_CORS`
finding, issue #4). A live, public service must be secure by default.

## Decision Drivers

- The frontend must be able to call the API from a browser.
- The API must not be callable from arbitrary origins.
- Allowed origins differ per environment (prod vs. local dev).

## Considered Options

- A config-driven explicit allowlist (chosen).
- A permissive wildcard (`*`).
- Terminate CORS at an external gateway.

## Decision Outcome

Chosen option: **an explicit allowlist bound from `zarlania.cors.allowed-origins`**
(overridable via `ZARLANIA_CORS_ALLOWED_ORIGINS`), never a wildcard. The list defaults to
the production frontend origins; local dev adds localhost via the env override. Invalid
configuration (empty list, blank entries, or a `*` wildcard) is rejected at bind time so a
misconfiguration fails fast rather than silently weakening CORS. Methods and headers are
scoped to the current GET-only API surface. This replaces the POC's permissive config and
resolves issue #4.

The application's built-in default allowlist is the **production origins only**
(`https://zarlania.com`, `https://www.zarlania.com`); localhost dev origins are supplied per
environment via `ZARLANIA_CORS_ALLOWED_ORIGINS` (e.g. in `docker-compose.yml`), rather than
baked into the default. The default is deliberately production-safe; dev origins are added
through the environment override instead.

### Consequences

- Good: secure-by-default CORS; origins are reviewable configuration; misconfiguration
  fails fast.
- Bad: the allowlist must be kept in sync with frontend origins as they change.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md`
- `docker-compose.yml` (sets the localhost dev origins via `ZARLANIA_CORS_ALLOWED_ORIGINS`)
- Issue #4 (CORS hardening)
- Issue #23 (FindSecBugs CORS detector NPE; `CorsConfigTest` is the compensating control)
