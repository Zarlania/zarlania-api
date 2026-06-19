---
id: '0011'
name: Keep domains decoupled in code with DB-level integrity
description: 'Keep domains decoupled in code (private entities, DTO boundaries, no cross-domain JPA associations) while enforcing referential integrity with database foreign keys.'
status: accepted
date_proposed: '2026-06-18'
date_accepted: '2026-06-18'
date_invalidated: null
author: stimothy
supersedes: []
superseded_by: []
tags:
- architecture
- persistence
---
# ADR-0011: Keep domains decoupled in code with DB-level integrity

<!-- adr-meta:start -->
| Field | Value |
| --- | --- |
| ID | 0011 |
| Name | Keep domains decoupled in code with DB-level integrity |
| Description | Keep domains decoupled in code (private entities, DTO boundaries, no cross-domain JPA associations) while enforcing referential integrity with database foreign keys. |
| Status | accepted |
| Date proposed | 2026-06-18 |
| Date accepted | 2026-06-18 |
| Date invalidated | — |
| Author | stimothy |
| Supersedes | — |
| Superseded by | — |
| Tags | architecture, persistence |
<!-- adr-meta:end -->

## Context and Problem Statement

As the API grows to multiple business domains (e.g. users, organizations, billing), we need
an explicit rule for how those domains relate to each other in code and in the database. Two
failure modes must be prevented: (1) tight coupling via cross-domain entity imports that
makes domains impossible to refactor independently, and (2) losing referential integrity by
having no enforcement at the database level when one domain stores a reference to another
domain's records.

## Decision Drivers

- Domain entity classes are internal implementation details; exposing them across domain
  boundaries creates hidden coupling that makes individual domains impossible to evolve
  independently.
- Cross-domain JPA associations (`@ManyToOne`, `@OneToMany`, `@ManyToMany`) force
  Hibernate session coupling and bind two domains' lifecycle and fetch strategies together.
- Referential integrity must still be enforced at rest; a "soft reference" (storing only a
  foreign ID with no database constraint) allows orphaned or dangling rows to accumulate
  silently.
- Cross-domain interaction must be explicit and in-process; internal HTTP calls between
  domains add network overhead, failure modes, and latency for no architectural benefit in a
  monolith.

## Considered Options

- Entities internal to each domain; DTOs cross boundaries; no cross-domain JPA associations;
  database-level FK for referential integrity; in-process Spring bean calls for cross-domain
  interaction
- Entities shared freely across domains (monolithic coupling — no boundary enforcement)
- Separate database per domain (microservice model — no shared FK possible, significant
  operational overhead)

## Decision Outcome

Chosen option: **entities internal, DTOs at boundaries, database FK for integrity,
in-process calls for cross-domain interaction**, because it preserves domain independence in
code while retaining the database's ability to enforce referential integrity, with no network
overhead for cross-domain reads.

The rules are:

1. **Entities are private to their domain.** Each domain owns its JPA entity classes. Code
   outside that domain must never import or hold a reference to another domain's entity class.
2. **No cross-domain JPA associations.** There are no cross-domain `@ManyToOne`,
   `@OneToMany`, or `@ManyToMany` annotations. If domain A must reference domain B's
   records, it stores only the ID as a plain column (`Long organizationId`) and declares the
   referential constraint as a `FOREIGN KEY` in a Flyway migration — not as a JPA
   association.
3. **Database-level FK enforces integrity.** The Flyway migration that introduces the
   referencing column also declares the `FOREIGN KEY` constraint, so the database rejects
   inserts or updates that would leave dangling references, even though JPA does not model
   the relationship as an object association.
4. **DTOs cross domain boundaries.** Plain Java records are the only objects passed between
   domains — used for controller responses and as arguments/return values in cross-domain
   service calls.
5. **Cross-domain interaction is an in-process call.** When domain A needs behaviour from
   domain B, it injects domain B's service interface as a Spring bean. No internal HTTP, no
   message broker for synchronous cross-domain reads.

### Consequences

- Good: Domains can be refactored, renamed, or independently extracted without touching
  other domains' entity classes.
- Good: Database FK constraints preserve referential integrity at rest without JPA-session
  coupling between domains.
- Good: In-process service calls are type-safe, have zero network overhead, and participate
  in the same transaction as the caller when needed.
- Good: The rule is mechanical and auditable — cross-domain entity imports can be caught by
  a static-analysis or CI grep rule.
- Bad: Fetching related data across domains requires an explicit service call or a manually
  written join query rather than a JPA lazy-load navigation.
- Bad: Developers accustomed to monolithic JPA models must learn to stop at the domain
  boundary and resist adding cross-domain associations.

## Links

- ADR-0010: Adopt Spring Data JPA with H2 and Flyway (establishes the JPA and Flyway
  foundation this boundary rule is built on)
