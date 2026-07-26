# Design: admin and machine tokens

- **Issue:** [#26](https://github.com/Zarlania/zarlania-api/issues/26)
- **Date:** 2026-07-25
- **Applies to:** `Zarlania/zarlania-api` only.
- **Spec chain:** 4 of 7 — the last backend spec. Predecessor:
  [spec 3 — general organizations, roles, and permissions](2026-07-25-general-orgs-roles-permissions-design.md)
  (the full decomposition is in spec 1). Successor:
  [spec 5 — theming and the landing page](https://github.com/Zarlania/zarlania-app/blob/master/docs/superpowers/specs/2026-07-25-theming-landing-page-design.md)
  in `Zarlania/zarlania-app`.

## Purpose

The operator's toolkit: system-admin roles granted at the user level, a
repeatable super-admin bootstrap, read-only impersonation tokens for
troubleshooting any organization, a service registry whose credentials
Zarlania's own future services exchange for short-lived org-scoped service
JWTs, and the audit log that makes all of it accountable.

## Scope

Delivered: the `admin` domain (system roles + grants, service registry,
audit log), bootstrap, impersonation minting, the service-token exchange,
and the admin endpoints. Out of scope: any admin UI (future), customer-facing
API keys for external integrations (future work, explicitly **not** service
tokens — see below), admin analytics.

## Admin role model (chosen approach)

**Separate user-level grant tables in a new `admin` domain.** System-admin
roles attach to a user globally — a different axis than spec 3's
org-membership roles — so they get their own small tables; the
`organizations` domain is untouched. Rejected: reusing spec 3's `roles`
tables with a scope discriminator (rows would stop meaning "role within an
org"; similar-looking is not duplicated knowledge) and boolean flags on
`users` (a migration per capability, admin coupling inside the users domain).

## Data model

All in the `admin` domain, on spec 1's conventions:

- **`system_roles`** + **`system_role_permissions`** — spec 3's shape on the
  user axis. Seeded roles and the catalog's new `admin.` namespace:

  | Role | Permissions |
  | ---- | ----------- |
  | `SUPER_ADMIN` | `admin.roles.manage`, `admin.services.manage`, `admin.impersonate`, `admin.audit.read` |
  | `SUPPORT` | `admin.impersonate`, `admin.audit.read` |

- **`user_system_roles`** — user FK id → system role, plus `granted_by`
  (user FK id); one row per grant.
- **`services`** — the registry of Zarlania's own services: unique name
  (e.g. `feature-toggles`), the permission strings that service is allowed
  to wield (chosen at registration from the catalog), `created_by`,
  `revoked_at`, `last_used_at`. Secrets live in a child table,
  **`service_secrets`** (service FK, SHA-256 secret hash, `revoked_at`) —
  at most **two active secrets per service**, so rotation is zero-downtime:
  add new, migrate the caller, retire old. Raw secrets are shown exactly
  once (spec 2's stored-token rule).
- **`audit_log`** — append-only: actor user id, action code
  (`admin.impersonation.minted`, `admin.role.granted`,
  `admin.service.registered`, …), target ids, `jsonb` detail. Written for
  every admin-domain mutation and every impersonation mint. Deliberately
  **not** written per service-token exchange (that is every ≤15 min per
  service — noise); `last_used_at` answers "is this service alive."

## Endpoints

| Endpoint | Requires |
| -------- | -------- |
| `POST /admin/users/{id}/system-roles`, `DELETE …/{role}` | `admin.roles.manage`; the last `SUPER_ADMIN` cannot be revoked (`admin.last-super-admin`) |
| `GET /admin/users?email=…` | `admin.roles.manage`; exact-match, filter required — spec 3's lookup idiom |
| `POST /admin/services` | `admin.services.manage`; body: name + permission subset; response carries the first raw secret once |
| `POST /admin/services/{id}/secrets` | `admin.services.manage`; issue the second secret for rotation (409 if two already active) |
| `DELETE /admin/services/{id}/secrets/{secretId}` | `admin.services.manage`; retire one secret |
| `GET /admin/services`, `DELETE /admin/services/{id}` | `admin.services.manage`; delete = revoke (`revoked_at`, never row deletion — audit referents survive) |
| `POST /admin/impersonation-tokens` | `admin.impersonate`; body: target org id |
| `GET /admin/audit` | `admin.audit.read`; paginated, filterable by actor/action/target |
| `POST /auth/token/service` | public, throttled like login; body: service id + secret + target org id |

**How admin powers reach `@PreAuthorize`:** at mint time — the same single
minting path — the user's system roles resolve into a separate
**`system_permissions` claim**, kept apart from the org-scoped `permissions`
claim so org semantics stay untouched. The JWT converter merges both into
authorities; `/admin/**` requires `admin.*` authorities. Stated honestly: an
admin's powers ride along in whatever org token they hold, and revoking a
system role has the same ≤15-minute staleness as any role change — same
trade, same TTL bound.

## Super-admin bootstrap (chosen approach)

Env-var promotion at startup: if `SUPER_ADMIN_EMAIL` is set, no `SUPER_ADMIN`
grant exists, and a **verified** user matches the email → grant
`SUPER_ADMIN` and write the audit entry (actor: system). Idempotent no-op on
every later boot. Repeatable by design: after each 30-day free-tier database
wipe, re-register, restart, promoted again — unattended. Rejected: a seed
migration (personal email baked into versioned SQL in a public repo) and a
one-time bootstrap-secret endpoint (extra exposed surface, manual ceremony
per wipe).

## The two new token kinds

Minted by the same token service as user tokens — one minting path, three
`kind`s — and both are **access-JWT-only: no refresh family, ever**. A longer
session means minting again through the audited, credentialed front door.

**Impersonation** (read-only — chosen over role-equivalent access:
troubleshooting means seeing what the org sees; fixes go through admin
endpoints with their own audit trail):

- Claims: `kind=impersonation`, `sub` = the admin's **real** user id
  (accountability — nothing pretends to be an org member), `org` = target,
  `permissions` = the read-only catalog subset (`members.read` and every
  future `*.read`, resolved by suffix filter so new read permissions flow in
  without touching this code).
- 15-minute TTL; audit entry on every mint (admin, org, `jti`).
- Resource-side nothing special — permissions do the limiting; the `kind`
  claim lets anything human-only discriminate.
- Works on any org type, including personal orgs — the "troubleshoot a
  user's account" case.

**Service tokens** — internal service-to-service auth for Zarlania's own
services, the lifted-out-domain future spec 1 plans for (e.g. a Feature
Toggle service reading another service's org-scoped state). **Not**
customer-facing API keys: the credential belongs to a *service*, and the org
is named per exchange.

- Client-credentials-style exchange: `POST /auth/token/service` with service
  id + secret + **target org id** → verify an active secret by hash, service
  unrevoked, org exists → `kind=service`, `sub` = the service id (there is
  no user), `org` = the named org, `permissions` = the service's registered
  subset. 15-minute TTL; `last_used_at` stamped.
- **One token per org, one credential per service** — a service touching N
  orgs' data holds N short-lived tokens (the original prompt's rule) but a
  single registered credential.
- Services are platform-internal and trusted across orgs: no per-org
  authorization list. What limits a service is *which permissions* it was
  registered with, not which orgs.
- Revocation reality: revoking a service or secret stops the next exchange;
  an already-minted JWT rides out its ≤15 minutes — the stateless trade
  applied consistently. Rotation = dual-secret overlap, zero downtime.

## Error handling

Same RFC 9457 + stable-code contract: `admin.last-super-admin`,
`admin.service-revoked`, `admin.secrets-limit` (409 on a third secret),
`auth.invalid-service-credentials` (uniform 401 on exchange — no
distinguishing unknown service / bad secret / revoked / org-gone; login's
non-enumeration stance). `/admin/**` without admin authorities returns
**`404`, not `403`** — spec 3's existence-hiding rule; even a 403 confirms
the routes exist.

## Testing

- **Unit** — read-only subset resolution (a future `things.read` flows in,
  `things.write` does not), last-super-admin guard, bootstrap state machine
  (no var / no match / unverified match / already granted → exactly one
  grant path), audit-entry composition, service permission subsetting,
  dual-secret rules (second secret allowed, third rejected, either active
  secret exchanges).
- **Integration (Testcontainers)** — seeded system roles present; bootstrap
  idempotency proven by running the startup hook twice against one database;
  audit rows append-only.
- **End-to-end (MockMvc)** — bootstrap admin → grant `SUPPORT` → support
  user impersonates a stranger's org → reads members, `403` on writes →
  audit shows grant and mint. Register service → exchange for org A →
  service JWT reads org A within its permissions → same credential
  exchanges for org B → rotate secrets (old + new both work during overlap,
  retired one stops) → revoke service → next exchange 401s. Negative space:
  non-admin on `/admin/**` gets `404`; impersonation/service JWTs fail at
  `/auth/refresh` (no family exists); a service JWT cannot call `/admin/**`.
- **Coverage:** the 80% JaCoCo gate stands.

## Decisions log

| Decision | Choice | Alternatives rejected |
| -------- | ------ | --------------------- |
| Admin role model | Separate tables in `admin` domain | Scope discriminator on org roles; flags on `users` |
| Bootstrap | `SUPER_ADMIN_EMAIL` promotion at startup, idempotent | Seed migration; bootstrap-secret endpoint |
| Impersonation power | Read-only (`*.read` subset) | Role-equivalent of admin's choice |
| Impersonation identity | `sub` = admin's real user id | Masquerading as a member |
| Service-token meaning | Internal service-to-service auth, credential per service, org named at exchange | Per-org API keys for external integrations (future work, distinct feature) |
| Service credential | Registered secret → 15-min JWT exchange | Long-lived JWT (denylist breaks statelessness) |
| Secret rotation | Dual active secrets, zero-downtime overlap | Single secret, hard cutover |
| Service issuance | System admins register services | Org-owner self-service (belongs to the future API-key feature, not this) |
| Admin claims | Separate `system_permissions` claim, merged to authorities | Mixing into org `permissions`; per-request DB checks |
| Exchange auditing | `last_used_at` only | Audit row per exchange (noise) |
