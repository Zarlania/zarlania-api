# Design: general organizations, roles, and permissions

- **Issue:** [#26](https://github.com/Zarlania/zarlania-api/issues/26)
- **Date:** 2026-07-25
- **Applies to:** `Zarlania/zarlania-api` only.
- **Spec chain:** 3 of 7. Predecessor:
  [spec 2 — users, personal organizations, and core authentication](2026-07-25-users-personal-orgs-core-auth-design.md)
  (the full decomposition is in spec 1). Successor:
  [spec 4 — admin and machine tokens](2026-07-25-admin-machine-tokens-design.md).

## Purpose

Let users collaborate: create general organizations, invite members with
roles, manage membership, and mint org-scoped tokens for whichever org they
are acting in. Introduces the roles/permissions machinery every later feature
authorizes against.

## Scope

Delivered: general-org creation, listing my orgs, the invitation lifecycle,
member management (change role, remove, leave), exact-match user lookup,
org-switch token minting, seeded roles + permission catalog, and the
`@PreAuthorize` enforcement wiring.

Deferred: org rename/delete (nothing org-scoped worth deleting exists yet;
arrives with vault/collection work), custom org-defined roles (schema-ready,
no endpoints), fuzzy user search (privacy default is exact-match only).

## Role model (chosen approach)

**Fixed roles now, custom-ready schema.** Built-in `OWNER` / `ADMIN` /
`MEMBER` roles are seeded rows, mapped to permissions in data, not code — so
org-defined custom roles later are additive rows in the same tables, not a
redesign. Rejected: custom roles from day one (role CRUD + guard-rail edge
cases with no user demand yet).

**Permission catalog v1** (code-owned enum, strings in the DB):
`org.manage`, `members.manage`, `members.read`.

| Role | Permissions |
| ---- | ----------- |
| `OWNER` | `org.manage`, `members.manage`, `members.read` |
| `ADMIN` | `members.manage`, `members.read` |
| `MEMBER` | `members.read` |

The catalog grows as later features (vaults, collections) add permissions.

**Owner invariants:**

- **General orgs: ≥ 1 `OWNER`, always.** Any demote/remove/leave that would
  strip the last owner fails (`orgs.last-owner`).
- **Personal orgs: exactly 1 `OWNER`, their sole member, forever.** All
  membership-mutating operations are rejected on `PERSONAL` orgs as a class
  (`orgs.personal-immutable`), which enforces the invariant for free.

## Data model

All in the `organizations` domain, on spec 1's column conventions:

- **`roles`** — seeded `OWNER`/`ADMIN`/`MEMBER` with `organization_id`
  **null** (built-in). Custom roles later: same table, org id set. Name
  unique per scope.
- **`role_permissions`** — role → permission string.
- **`organization_invitations`** — org, inviter + invitee (user FK ids),
  offered role, status (`PENDING`/`ACCEPTED`/`DECLINED`/`REVOKED`), 14-day
  expiry; at most one `PENDING` per (org, invitee).
- **`organization_memberships`** — spec 2's `is_owner` boolean is **replaced
  by `role_id`** via additive migration: add column, backfill `OWNER` where
  the flag was set (`MEMBER` otherwise), drop the flag. One role per
  membership — tiers, not stacks. Personal-org memberships hold `OWNER`, so
  personal and general orgs share one permission path with no special-casing.

## Endpoints

| Endpoint | Permission / rule |
| -------- | ----------------- |
| `POST /organizations` | any authenticated user; creates a general org, creator becomes `OWNER` |
| `GET /organizations` | own memberships: every org I belong to, with my role |
| `GET /organizations/{id}/members` | `members.read`, only with a token scoped to that org |
| `POST /organizations/{id}/invitations` | `members.manage`; body: invitee + role; offering `OWNER` requires being an `OWNER` |
| `DELETE /organizations/{id}/invitations/{invId}` | `members.manage` (revoke a pending invitation) |
| `GET /invitations` | my pending invitations (invitee side) |
| `POST /invitations/{id}/accept`, `POST /invitations/{id}/decline` | invitee only; accept creates the membership with the offered role |
| `PATCH /organizations/{id}/members/{userId}` | `members.manage`; promoting to or demoting an `OWNER` requires being `OWNER`; last-owner guard |
| `DELETE /organizations/{id}/members/{userId}` | `members.manage`; removing an `OWNER` requires `OWNER`; last-owner guard |
| `DELETE /organizations/{id}/members/me` | leave — any member; last-owner guard |
| `GET /users?username=…` | exact-match lookup for inviting; filter **required** (unfiltered listing is `400`); returns a list of 0 or 1 `{id, username}` |

Privacy defaults: no substring search, no user-directory browsing, emails
never searchable. The list-shaped lookup response stays honest if a fuzzier
search loosens the filter later.

**Org-creation quota:** per-user cap on general orgs owned (config value,
default 10) — free-tier hygiene against a script filling the unique-name
namespace (`orgs.quota-exceeded`).

**Naming:** org names share one `citext`-unique namespace with personal orgs
(= usernames); a taken name is an honest `409` (`orgs.name-taken`) — org
names are public, same reasoning as usernames in spec 2.

## Tokens and authorization wiring

**Enforcement (chosen approach): declarative method security.** Spring
Security maps the JWT's permissions claim to authorities;
`@PreAuthorize("hasAuthority('members.manage')")` guards each protected
operation at the method that implements it. Content-dependent rules —
owner-only promote/demote, the last-owner guard — are explicit service-level
checks. Rejected: imperative `access.require(...)` at every call site
(forgettable boilerplate) and a central route→permission table (cannot
express content-dependent rules; degenerates into a hybrid).

- **Claims:** minting resolves the member's role in the target org to
  `"role": "ADMIN"` (display) and
  `"permissions": ["members.manage", …]` (enforcement). Request handling
  stays DB-free (spec 2's stateless rule). Staleness bound: role changes take
  effect on next mint — worst case the 15-minute access-token TTL.
- **Org switching: `POST /auth/token`** (auth domain, beside spec 2's
  endpoints). Body names the target org; caller must hold a valid refresh
  cookie. Verifies membership, then mints a fresh access JWT **and a new
  refresh family scoped to that org** — the spec-2 rule that a JWT never
  crosses orgs. The previous org's family is revoked: one active org per
  browser session, matching the one-org-at-a-time UX. Login still defaults
  to the personal org; switching is an explicit act.
- **Spec-2 touch-up:** login/refresh minting now also resolves the
  permissions claim (personal org → `OWNER`'s permissions), keeping exactly
  one token-minting path for every org type.

## Error handling

Same RFC 9457 + stable-code contract as spec 2: `orgs.last-owner`,
`orgs.personal-immutable`, `orgs.name-taken`, `orgs.quota-exceeded`,
`invitations.already-pending`, `invitations.expired`, ….

One deliberate semantic: acting on an org you are **not a member of returns
`404`, not `403`** — a 403 would confirm the org id exists; non-members do
not get to learn that.

## Testing

- **Unit** — invariant guards in isolation: last-owner protection
  (demote/remove/leave), personal-org immutability, owner-only
  promote/demote, permission resolution from role (including the
  personal-org `OWNER` path), invitation state transitions.
- **Integration (Testcontainers)** — the `is_owner → role_id` backfill
  migration against spec-2-shaped data; seeded roles/permissions present
  after migration; repository slices for the new tables.
- **End-to-end (MockMvc)** — create org → look up user → invite → invitee
  lists it → accept → member appears with role → `POST /auth/token` switch →
  act under the new org's permissions → `MEMBER` gets `403` on
  `members.manage` operations → promote a second owner → original owner
  leaves → last-owner guard fires for the final one. Plus: declined, expired
  (fixed `Clock`), and revoked invitations; `404` for non-members; quota
  `409`; and the cross-org rule — a token scoped to org A hitting org B's
  member list gets `404`.
- **Coverage:** the 80% JaCoCo gate stands.

## Decisions log

| Decision | Choice | Alternatives rejected |
| -------- | ------ | --------------------- |
| Role model | Fixed `OWNER`/`ADMIN`/`MEMBER`, custom-ready schema | Custom roles from day one |
| Join flow | Invitation + acceptance | Direct add (unconsented membership) |
| Enforcement | `@PreAuthorize` on permissions claim | Imperative checks; route→permission table |
| Roles per membership | Exactly one (tiers) | Multiple roles (stacks) |
| User search | `GET /users?username=` exact match, filter required | `/users/lookup` path; substring search; email search |
| Non-member access | `404` (hide existence) | `403` (leaks org ids) |
| Org switch | New access JWT + new refresh family, old family revoked | Multiple live families per browser session |
| Org rename/delete | Deferred | In scope now |

## Amendment — spec 2 as implemented (2026-08-02)

Spec 2's implementation (PR #33) deviated from spec 2's own text in ways this
spec's design relies on. The deviations were reviewed and kept; carry them
into implementation rather than the wording above:

- **CSRF machinery exists after all.** Spec 2 said "no CSRF-token
  machinery"; the implementation ships scoped double-submit protection on
  the cookie-authenticated routes (`POST /auth/refresh`,
  `POST /auth/logout`), with an `HttpOnly` CSRF cookie and the token served
  by `GET /auth/csrf`. `POST /auth/token` authenticates with the same
  refresh cookie, so it must be added to `SecurityConfig`'s
  `requireCsrfProtectionMatcher`, and the client must echo the
  `X-XSRF-TOKEN` header there too. Test support exists
  (`testsupport/CsrfCredentials`).
- **Refresh re-checks the user.** `AuthTokenService.refresh` re-checks the
  user still exists and is verified after rotating, revoking the
  just-rotated family before rejecting. `POST /auth/token` mints through the
  same rotation and must apply the same re-check alongside the membership
  check this spec adds.
- **Throttling is an idiom, not an afterthought.** Every public `/auth`
  route is rate-limited per client IP (`ClientIpResolver`, keyed on
  `CF-Connecting-IP`); `POST /auth/token` needs its own limit in
  `ThrottleProperties` like the rest.
- **This spec is where the per-organization throttle bucket lands.** Core auth
  shipped two bucket kinds — per client IP, and per account identifier named in
  the request body — because every endpoint it throttled was public and had no
  better identity available. This spec introduces both authenticated throttled
  endpoints and shared organizations, so it should add:
  - a **per-user** bucket keyed on the access token's `sub` claim, which bounds
    one account across every address it calls from without trusting anything the
    caller typed;
  - a **per-organization** bucket keyed on the `org` claim, so a general org gets
    one budget rather than one budget per member — which is the whole reason it
    only becomes meaningful here.

  `EndpointLimits` currently exposes exactly one optional bucket
  (`accountLimitIfPresent()`); adding these means that stops being the general
  test for "is there a second bucket", and `ThrottleAspect` gains the claim reads.
  See `EndpointLimits`' own Javadoc, which records the same plan next to the code.
- **`/auth/**` is `permitAll` by path.** A new route under `/auth` is public
  as far as the resource server is concerned; `POST /auth/token`'s only
  authentication is the refresh-cookie rotation itself, exactly as with
  `/auth/refresh`.
- **Refresh-token reuse is logged** with the `REFRESH_TOKEN_REUSE` WARN
  marker in `RefreshTokenService.rotate`. The org-switch flow's family
  revocation is an ordinary revocation, not a theft signal — do not route it
  through that marker.
