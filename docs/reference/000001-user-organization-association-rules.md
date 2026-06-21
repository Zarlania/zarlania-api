---
id: "000001"
title: User–organization association rules
description: How users and organizations relate — personal vs general orgs, ownership, and uniqueness
tags:
- architecture
- domain-model
created: 2026-06-21
updated: 2026-06-21
related:
- ADR-0010
- ADR-0011
- com.zarlania.api.users
- com.zarlania.api.organizations
---
# User–organization association rules

<!-- ref-meta:start -->
| Field | Value |
| --- | --- |
| ID | 000001 |
| Title | User–organization association rules |
| Description | How users and organizations relate — personal vs general orgs, ownership, and uniqueness |
| Tags | architecture, domain-model |
| Created | 2026-06-21 |
| Updated | 2026-06-21 |
| Related | ADR-0010, ADR-0011, com.zarlania.api.users, com.zarlania.api.organizations |
<!-- ref-meta:end -->

## Overview

Organizations are the ownership root of the service: data built into the system is tied to an
organization. This doc explains how the `users` and `organizations` domains relate — it
describes behaviour, it does not decide it. The boundary decisions live in ADR-0011
(decoupled domains, DB-level integrity) and ADR-0010 (persistence); the code lives in
`com.zarlania.api.users` and `com.zarlania.api.organizations`.

## Scope

Covers the association rules and invariants between users and organizations as they exist
today: personal vs general organizations, ownership and membership, and the uniqueness
guarantees. Out of scope (and deferred to a future `identity` domain): account-creation
orchestration that creates a user and their personal org atomically, cross-domain existence
checks, permission gates, and authentication/secrets.

## Rules / constraints

### Personal organizations

- A user has a one-to-one relationship with a single `PERSONAL` organization. Today the
  `organizations` domain enforces only the uniqueness half — it rejects creating a second
  personal org for an owner who already has one, so a user has **at most one** personal org.
  The other half (that one always exists) belongs to the future account-creation
  orchestration; once it lands, the two halves together yield **exactly one** personal org
  per user.
- A personal organization has exactly one membership: its owner, with role `OWNER`. No other
  members and no additional owners may be added (`addMember` / `addOwner` are rejected for
  `PERSONAL` orgs).
- A personal organization is intended to be named after the owner's unique `username` (via
  the future account-creation orchestration), so that the global organization-name uniqueness
  constraint also DB-backs the one-personal-org-per-user rule.

### General organizations

- A user can create `GENERAL` organizations (company accounts) and becomes an `OWNER`. Owning
  a general org is independent of owning the personal org, so a user can own two or more orgs.
- A general organization may have multiple `OWNER` memberships and multiple non-owner `MEMBER`
  memberships.

### Always at least one owner

- Every organization has at least one `OWNER` at all times — exactly one for `PERSONAL`, one
  or more for `GENERAL`. No organization may ever reach zero owners; any future operation that
  could remove or demote an owner must reject the change when it would leave the org ownerless.

### Uniqueness

- A user's email is unique (one account per email).
- A user's `username` is unique.
- Organization names are unique across all organizations, enforced by a database `UNIQUE`
  constraint and surfaced as a domain-level "name already exists" failure.

### Boundary

- `users` and `organizations` are independent in code: neither creates the other's entities,
  and `organizations` references a user only by opaque `userId` (it never imports or loads the
  `User` entity). The only link between them is the database foreign key
  `membership.user_id` → `users.id`, declared in a migration (see ADR-0011).

## Related

- ADR-0010 — persistence foundation (Spring Data JPA, H2, Flyway).
- ADR-0011 — decoupled domains in code with DB-level referential integrity.
- `com.zarlania.api.users` — the `users` domain (entity, repository, DTO, service).
- `com.zarlania.api.organizations` — the `organizations` domain and its invariants.
