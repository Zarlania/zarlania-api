---
id: '0006'
name: Adopt Java 25, Spring Boot, and Maven with Docker
description: 'Pin Java 25 LTS, Spring Boot 4.1.x, Maven wrapper, and a multi-stage Temurin Docker image.'
status: proposed
date_proposed: '2026-06-16'
date_accepted: null
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- build
- configuration
---
# ADR-0006: Adopt Java 25, Spring Boot, and Maven with Docker

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0006 |
| Name | Adopt Java 25, Spring Boot, and Maven with Docker |
| Description | Pin Java 25 LTS, Spring Boot 4.1.x, Maven wrapper, and a multi-stage Temurin Docker image. |
| Status | proposed |
| Date proposed | 2026-06-16 |
| Date accepted | — |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | build, configuration |
<!-- adr-meta:end -->

## Context and Problem Statement

The service needs a pinned language, framework, and build toolchain. The repo-shell spec
baselined Java 25 / Spring Boot 3.5.x / Maven / Docker, with a stated policy to pick the
**latest compatible** versions at implementation time to minimize first-Dependabot churn.

## Decision Drivers

- Long-term support and security longevity (LTS runtime).
- Latest-compatible dependencies, to minimize churn right after the first release.
- Reproducible, reviewable builds and a small, non-root runtime image.

## Considered Options

- Java 25 LTS + Spring Boot 4.1.x + Maven wrapper + multi-stage Temurin Docker (chosen).
- Spring Boot 3.5.x (the spec's original baseline).
- Gradle instead of Maven.

## Decision Outcome

Chosen: **Java 25 (LTS), Spring Boot 4.1.x, Maven via `./mvnw`, multi-stage Temurin 25
Docker (jdk build → jre run, non-root), springdoc-openapi 3.0.3.** Per the spec's
latest-compatible policy this lands on Spring Boot **4.1.0** rather than the 3.5.x baseline
the spec table named; this divergence is intentional and accepted.

### Consequences

- Good: newest features and security fixes; minimal initial dependency churn; LTS runtime.
- Bad: Spring Boot 4.x is newer with a shorter community track record; the spec's stack
  table (§32) is now historical and should be read together with this ADR.

## Links

- Spec: `docs/superpowers/specs/2026-06-15-zarlania-api-repo-shell-design.md` §32
- `pom.xml`, `Dockerfile`
