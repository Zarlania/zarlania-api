# Admin & Machine Tokens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-25-admin-machine-tokens-design.md`
**Depends on:** spec 3's implementation merged to master.

**Goal:** The `admin` domain — user-level system roles with an idempotent super-admin bootstrap, an append-only audit log, read-only impersonation tokens, and a service registry whose dual secrets exchange for short-lived org-scoped `kind=service` JWTs.

**Architecture:** System roles mirror spec 3's shape on the user axis (`system_roles` + association permissions + `user_system_roles`). Minting takes a single `TokenClaims` record; user tokens gain a `system_permissions` claim resolved from grants; impersonation and service tokens are access-JWT-only (no refresh family). `/admin/**` denials are 404, not 403.

**Tech Stack:** unchanged from plans 2–3.

## Global Constraints

- Plans 1–3 Global Constraints apply (wrapper, gates, commit format `#<ISSUE> <type>: …`, `Clock`-only time, ProblemDetail codes, existence-hiding 404s).
- System permission strings (exact): `admin.roles.manage`, `admin.services.manage`, `admin.impersonate`, `admin.audit.read`. System roles (exact): `SUPER_ADMIN` (all four), `SUPPORT` (`admin.impersonate`, `admin.audit.read`).
- Token kinds (constants already in `TokenKinds`): user `"user"`, impersonation `"impersonation"`, service `"service"`. Impersonation/service tokens never get a refresh family.
- Impersonation permissions = every catalog permission ending `.read` (suffix filter over `PermissionCatalog.all()`).
- Audit action codes (exact): `admin.role.granted`, `admin.role.revoked`, `admin.impersonation.minted`, `admin.service.registered`, `admin.service.revoked`, `admin.service.secret-added`, `admin.service.secret-retired`. No audit row per service-token exchange — `last_used_at` covers liveness.
- New error codes: `admin.last-super-admin` (409), `admin.secrets-limit` (409), `auth.invalid-service-credentials` (401, uniform — never distinguishes unknown service / bad secret / revoked / org-gone). `/admin/**` without the required authority → 404 `orgs.not-found`-style hiding, code `admin.not-found` (404).
- At most **two** active (unrevoked) secrets per service.
- Association tables (`system_role_permissions`, `service_permissions`) carry no id/audit columns — same documented exception as `role_permissions`.

---

### Task 0: Tracking issue and branch

- [ ] **Step 1:**

```bash
gh issue create --title "feat: system admin roles, impersonation, and service tokens" --label feature --body "$(cat <<'EOF'
### Problem

There is no operator tooling: no way to bootstrap an administrator, troubleshoot an organization, or let Zarlania services authenticate to each other.

### Proposed solution

Implement docs/superpowers/specs/2026-07-25-admin-machine-tokens-design.md: the admin domain (system roles, grants, audit log, service registry), SUPER_ADMIN_EMAIL bootstrap, read-only impersonation tokens, and the client-credentials service-token exchange with dual-secret rotation.

### Alternatives considered

Reusing org-role tables with a scope discriminator; boolean admin flags on users; long-lived service JWTs — rejected in the spec's decisions log.

### Is this a breaking change?

No — backwards compatible

### Additional context

Spec 4 of 7 — the last backend spec.

### Before submitting

- [x] I searched existing issues and discussions and this is not a duplicate.
- [x] I agree to follow this project's Code of Conduct.
EOF
)"
git fetch origin master && git checkout -b <ISSUE>-admin-tokens origin/master
```

---

### Task 1: Migration V3 — the admin tables

**Files:**
- Create: `src/main/resources/db/migration/V3__admin_and_services.sql`
- Test: extend `ZarlaniaApiApplicationTest` with a table-presence assertion (same pattern as plan 2 Task 2: count the six new tables `system_roles`, `system_role_permissions`, `user_system_roles`, `services`, `service_permissions`, `service_secrets`, `audit_log` — that is seven — expected 7).

- [ ] **Step 1: Failing test, then the migration**

```sql
CREATE TABLE system_roles (
    id         uuid PRIMARY KEY,
    name       text NOT NULL UNIQUE,
    created_at timestamptz(6) NOT NULL,
    updated_at timestamptz(6) NOT NULL
);

-- Association table: documented exception to the standard-columns rule.
CREATE TABLE system_role_permissions (
    system_role_id uuid NOT NULL REFERENCES system_roles (id),
    permission     text NOT NULL,
    PRIMARY KEY (system_role_id, permission)
);

INSERT INTO system_roles (id, name, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000010', 'SUPER_ADMIN', now(), now()),
    ('00000000-0000-0000-0000-000000000011', 'SUPPORT', now(), now());

INSERT INTO system_role_permissions (system_role_id, permission) VALUES
    ('00000000-0000-0000-0000-000000000010', 'admin.roles.manage'),
    ('00000000-0000-0000-0000-000000000010', 'admin.services.manage'),
    ('00000000-0000-0000-0000-000000000010', 'admin.impersonate'),
    ('00000000-0000-0000-0000-000000000010', 'admin.audit.read'),
    ('00000000-0000-0000-0000-000000000011', 'admin.impersonate'),
    ('00000000-0000-0000-0000-000000000011', 'admin.audit.read');

CREATE TABLE user_system_roles (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id),
    system_role_id uuid NOT NULL REFERENCES system_roles (id),
    -- NULL means the system itself granted it (bootstrap).
    granted_by     uuid REFERENCES users (id),
    created_at     timestamptz(6) NOT NULL,
    updated_at     timestamptz(6) NOT NULL,
    UNIQUE (user_id, system_role_id)
);

CREATE TABLE services (
    id           uuid PRIMARY KEY,
    name         citext NOT NULL UNIQUE,
    created_by   uuid NOT NULL REFERENCES users (id),
    revoked_at   timestamptz(6),
    last_used_at timestamptz(6),
    created_at   timestamptz(6) NOT NULL,
    updated_at   timestamptz(6) NOT NULL
);

CREATE TABLE service_permissions (
    service_id uuid NOT NULL REFERENCES services (id),
    permission text NOT NULL,
    PRIMARY KEY (service_id, permission)
);

CREATE TABLE service_secrets (
    id          uuid PRIMARY KEY,
    service_id  uuid NOT NULL REFERENCES services (id),
    secret_hash text NOT NULL UNIQUE,
    revoked_at  timestamptz(6),
    created_at  timestamptz(6) NOT NULL,
    updated_at  timestamptz(6) NOT NULL
);

CREATE TABLE audit_log (
    id            uuid PRIMARY KEY,
    -- NULL actor means the system itself (e.g. bootstrap).
    actor_user_id uuid REFERENCES users (id),
    action        text NOT NULL,
    detail        jsonb NOT NULL,
    created_at    timestamptz(6) NOT NULL,
    updated_at    timestamptz(6) NOT NULL
);
CREATE INDEX idx_audit_log_action ON audit_log (action);
CREATE INDEX idx_audit_log_actor ON audit_log (actor_user_id);
```

- [ ] **Step 2: Pass → commit**

```bash
./mvnw test -Dtest=ZarlaniaApiApplicationTest && ./mvnw spotless:apply
git add src/main/resources/db/migration src/test/java
git commit -m "#<ISSUE> feat: add the admin, service, and audit schema with seeded system roles"
```

---

### Task 2: Admin domain core — grants, audit, catalog

**Files:**
- Create: `src/main/java/com/zarlania/api/admin/entities/SystemRole.java`, `UserSystemRole.java`, `AuditEntry.java`
- Create: `src/main/java/com/zarlania/api/admin/repositories/SystemRoleRepository.java`, `UserSystemRoleRepository.java`, `AuditEntryRepository.java`
- Create: `src/main/java/com/zarlania/api/admin/services/SystemRoleService.java`, `AuditLogService.java`
- Create: `src/main/java/com/zarlania/api/admin/dtos/AuditEntryDto.java`
- Modify: `src/main/java/com/zarlania/api/common/security/Permissions.java` — add the four `admin.*` constants and `PermissionCatalog`
- Test: `src/test/java/com/zarlania/api/admin/services/SystemRoleServiceTest.java` (integration, container pattern)

**Interfaces:**
- Produces:
  - `SystemRole` (mirrors spec 3's `Role`: `name`, `@ElementCollection` `permissions` from `system_role_permissions`); `UserSystemRole` (`userId`, in-domain `@ManyToOne SystemRole role`, `grantedBy` nullable UUID); `AuditEntry` (`actorUserId` nullable, `action`, `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") Map<String, Object> detail`).
  - `SystemRoleService`: `void grant(UUID actorUserId, UUID targetUserId, String roleName)` (idempotent — existing grant is a no-op; audits `admin.role.granted`), `void revoke(UUID actorUserId, UUID targetUserId, String roleName)` (revoking the last `SUPER_ADMIN` grant in the system → `ApiException(ADMIN_LAST_SUPER_ADMIN)`; audits `admin.role.revoked`), `Set<String> systemPermissionsOf(UUID userId)` (union over grants; empty set for none), `boolean anySuperAdminExists()`.
  - `AuditLogService`: `void record(UUID actorUserIdOrNull, String action, Map<String, Object> detail)`; `Page<AuditEntryDto> query(Optional<UUID> actor, Optional<String> action, Pageable page)`. `AuditEntryDto(UUID id, UUID actorUserId, String action, Map<String, Object> detail, Instant at)`.
  - `PermissionCatalog` (in `common/security`): `static Set<String> allOrgPermissions()` returning the three org strings; `static Set<String> readOnlyOrgPermissions()` = suffix-filter `.endsWith(".read")`. Org and admin namespaces stay separate methods so impersonation only ever draws from the org catalog.

- [ ] **Step 1: Failing tests**

`SystemRoleServiceTest`: grant SUPPORT → `systemPermissionsOf` = the two SUPPORT strings; grant SUPER_ADMIN to A and SUPPORT to B → revoking A's SUPER_ADMIN throws `admin.last-super-admin`; grant SUPER_ADMIN to B too → revoking A's now succeeds and audits; double-grant is a no-op (one row); audit rows appear with actions and detail containing `targetUserId` and `role`.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=SystemRoleServiceTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: system role grants with audit trail and last-super-admin guard"
```

---

### Task 3: `TokenClaims` and the `system_permissions` claim

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/services/TokenClaims.java`
- Modify: `JwtService`, `AuthTokenService`, `SecurityConfig` (converter)
- Test: extend `JwtServiceTest`; new `AdminAuthoritiesTest` (integration)

**Interfaces:**
- Produces: `record TokenClaims(UUID userId, UUID organizationId, String kind, String role, Set<String> permissions, Set<String> systemPermissions)` — `JwtService.mint(TokenClaims claims)` replaces the five-arg overload everywhere (claims `role` omitted when null; `system_permissions` claim emitted only when non-empty). `AuthTokenService.mint(...)` resolves `systemRoleService.systemPermissionsOf(userId)` on every user-token mint (login, refresh, switch). Converter: authorities = union of `permissions` and `system_permissions` claims.

- [ ] **Step 1: Failing tests → implement → commit**

`JwtServiceTest` additions: `system_permissions` present when set, absent when empty. `AdminAuthoritiesTest`: seed a user, grant SUPPORT (service call), login (via `AuthTestHelper`) → decode: token carries `system_permissions` = SUPPORT's; a plain user's token has no such claim.

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: carry system permissions in a dedicated JWT claim"
```

---

### Task 4: Super-admin bootstrap

**Files:**
- Create: `src/main/java/com/zarlania/api/admin/services/SuperAdminBootstrap.java`
- Modify: `application.yml` (`zarlania.admin.super-admin-email: ${SUPER_ADMIN_EMAIL:}`), `render.yaml` (`SUPER_ADMIN_EMAIL`, `sync: false`), `.env.example` (commented `#SUPER_ADMIN_EMAIL=`)
- Test: `src/test/java/com/zarlania/api/admin/services/SuperAdminBootstrapTest.java`

**Interfaces:**
- Produces: `SuperAdminBootstrap implements ApplicationRunner` — on startup: email configured AND `!anySuperAdminExists()` AND a **verified** user matches → `grant(null, userId, "SUPER_ADMIN")` (actor null = system; the audit's `admin.role.granted` detail includes `"bootstrap": true`). Every other combination logs at INFO and does nothing.

- [ ] **Step 1: Failing test — the state machine**

Call `bootstrap.run(null)` directly (inject the bean) under each state: no email configured → no grant; email set, user missing → none; user unverified → none; verified user → grant exists + audit row; run twice → still one grant; a super-admin already exists (granted to someone else) → a *different* configured email is NOT granted.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=SuperAdminBootstrapTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java src/main/resources/application.yml render.yaml .env.example
git commit -m "#<ISSUE> feat: idempotent SUPER_ADMIN_EMAIL bootstrap at startup"
```

---

### Task 5: Admin endpoints and 404-hiding

**Files:**
- Create: `src/main/java/com/zarlania/api/admin/controllers/AdminUserController.java`, `AdminAuditController.java`
- Create: `src/main/java/com/zarlania/api/admin/dtos/GrantRoleRequest.java`, `AdminUserDto.java`
- Modify: `GlobalExceptionHandler`
- Test: `src/test/java/com/zarlania/api/admin/controllers/AdminEndpointsTest.java`

**Interfaces:**
- Produces:
  - `POST /admin/users/{id}/system-roles` `GrantRoleRequest(String role)` → 204; `DELETE /admin/users/{id}/system-roles/{role}` → 204 — both `@PreAuthorize("hasAuthority('admin.roles.manage')")`.
  - `GET /admin/users?email=…` → 0–1 `AdminUserDto(UUID id, String email, String username, boolean emailVerified)`; filter required (400 without) — `admin.roles.manage`.
  - `GET /admin/audit?actor=&action=&page=&size=` → page of `AuditEntryDto` — `admin.audit.read`.
  - `GlobalExceptionHandler` gains: `@ExceptionHandler(AuthorizationDeniedException.class)` (Spring Security's method-security denial) → if the request URI starts with `/admin` → 404 with code `admin.not-found`; otherwise 403 with code `auth.forbidden` (add both to `ErrorCode`).

- [ ] **Step 1: Failing test**

A SUPER_ADMIN session (bootstrap or direct grant + login) can: look up a user by email, grant SUPPORT, see both audit rows, revoke it. A plain user hitting any `/admin/**` endpoint → **404** `admin.not-found` (assert status and code). A SUPPORT session hitting `POST /admin/users/{id}/system-roles` → 404 (lacks `admin.roles.manage`); hitting `GET /admin/audit` → 200. Missing email filter → 400.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=AdminEndpointsTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: admin role and audit endpoints hidden behind 404s"
```

---

### Task 6: Service registry with dual secrets

**Files:**
- Create: `src/main/java/com/zarlania/api/admin/entities/RegisteredService.java`, `ServiceSecret.java`
- Create: `src/main/java/com/zarlania/api/admin/repositories/RegisteredServiceRepository.java`, `ServiceSecretRepository.java`
- Create: `src/main/java/com/zarlania/api/admin/services/ServiceRegistry.java`
- Create: `src/main/java/com/zarlania/api/admin/controllers/AdminServiceController.java`
- Create: `src/main/java/com/zarlania/api/admin/dtos/RegisterServiceRequest.java`, `RegisteredServiceDto.java`, `IssuedSecretDto.java`
- Test: `src/test/java/com/zarlania/api/admin/controllers/ServiceRegistryTest.java`

**Interfaces:**
- Produces:
  - Entities: `RegisteredService` (name citext-unique, `@ElementCollection` permissions from `service_permissions`, `createdBy`, `revokedAt`, `lastUsedAt`); `ServiceSecret` (in-domain `@ManyToOne RegisteredService service`, `secretHash` unique, `revokedAt`).
  - `ServiceRegistry`: `IssuedSecretDto register(UUID actor, String name, Set<String> permissions)` (permissions must be ⊆ `PermissionCatalog.allOrgPermissions()` → else 400 `validation.failed`; audits `admin.service.registered`; returns the service + first raw secret); `IssuedSecretDto addSecret(UUID actor, UUID serviceId)` (two active → 409 `admin.secrets-limit`; audits `admin.service.secret-added`); `void retireSecret(UUID actor, UUID serviceId, UUID secretId)` (audits); `void revoke(UUID actor, UUID serviceId)` (audits); `List<RegisteredServiceDto> list()`; and for Task 7's exchange: `Optional<ServiceGrant> authenticate(String serviceId, String rawSecret)` where `record ServiceGrant(UUID serviceId, Set<String> permissions)` — matches an **active** secret of an **unrevoked** service by hash, stamps `lastUsedAt`.
  - Endpoints (all `admin.services.manage`): `POST /admin/services` → 201 `IssuedSecretDto(RegisteredServiceDto service, UUID secretId, String rawSecret)` (raw shown once); `POST /admin/services/{id}/secrets` → 201; `DELETE /admin/services/{id}/secrets/{secretId}` → 204; `GET /admin/services` → 200; `DELETE /admin/services/{id}` → 204.

- [ ] **Step 1: Failing test**

Register `feature-toggles` with `{members.read}` → 201 with a raw secret; registering with permission `admin.impersonate` → 400; add a second secret ok, a third → 409 `admin.secrets-limit`; `authenticate` succeeds with either active secret and stamps `last_used_at`; retire the first → it stops authenticating, second still works; revoke the service → neither works; every mutation audited; non-admin → 404.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=ServiceRegistryTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: service registry with dual rotating secrets"
```

---

### Task 7: Impersonation and service token minting

**Files:**
- Create: `src/main/java/com/zarlania/api/admin/controllers/ImpersonationController.java`
- Create: `src/main/java/com/zarlania/api/admin/dtos/ImpersonationRequest.java`
- Modify: `src/main/java/com/zarlania/api/auth/controllers/AuthController.java` (service exchange endpoint)
- Create: `src/main/java/com/zarlania/api/auth/dtos/ServiceTokenRequest.java`
- Modify: `AuthTokenService`
- Test: `src/test/java/com/zarlania/api/admin/controllers/MachineTokensTest.java`

**Interfaces:**
- Produces:
  - `POST /admin/impersonation-tokens` `ImpersonationRequest(UUID organizationId)` — `@PreAuthorize("hasAuthority('admin.impersonate')")`; org must exist (404 `orgs.not-found` otherwise, and personal orgs are valid targets); returns `TokenResponse` (no cookie). Mint: `TokenClaims(adminUserId, orgId, TokenKinds.IMPERSONATION, null, PermissionCatalog.readOnlyOrgPermissions(), Set.of())`; audits `admin.impersonation.minted` with detail `{organizationId, jti}`.
  - `POST /auth/token/service` `ServiceTokenRequest(UUID serviceId, String secret, UUID organizationId)` — public, throttled (`zarlania.throttle.service-limit`, default 30, added to `ThrottleProperties`); `ServiceRegistry.authenticate` + org existence check; any failure → 401 `auth.invalid-service-credentials` uniformly. Mint: `TokenClaims(serviceId-as-sub …)` — `TokenClaims.userId` is typed UUID and carries the service id here; `kind = TokenKinds.SERVICE`; permissions = the grant's; no role, no system permissions. Returns `TokenResponse`, no cookie.
  - `TokenKinds` gains `IMPERSONATION = "impersonation"`, `SERVICE = "service"`.

- [ ] **Step 1: Failing test**

SUPPORT admin mints impersonation into a stranger's org → 200; decoded claims: `kind=impersonation`, `sub` = the admin's id, permissions exactly the `.read` subset; the token reads `GET /organizations/{id}/members` (200) but `POST …/invitations` → 403 (no `members.manage`); minting for a personal org works; for a nonexistent org → 404; a non-admin → 404; audit row exists. Service path: register service (Task 6 helper) → exchange with secret + org → 200; decoded: `kind=service`, `sub` = service id, permissions = registered set; wrong secret / revoked service / unknown org → identical 401 `auth.invalid-service-credentials`; exchanged token reads members (has `members.read`) but gets 404 on `/admin/services` (no admin authority); **neither token kind can refresh**: `POST /auth/refresh` with no cookie → 401 (nothing was ever set); `/auth/token` org-switch with an impersonation access token but no cookie → 401.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=MachineTokensTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api src/main/resources/application.yml
git commit -m "#<ISSUE> feat: read-only impersonation and service token exchange"
```

---

### Task 8: Journey, docs, PR

- [ ] **Step 1: Journey test** (`src/test/java/com/zarlania/api/AdminJourneyTest.java`)

Bootstrap super-admin (set the property in the test's `@DynamicPropertySource`, register+verify the user, run the bootstrap bean) → login → grant SUPPORT to a second verified user → SUPPORT logs in → impersonates an unrelated user's personal org → reads, cannot write → audit shows the grant and the mint → SUPER_ADMIN registers `feature-toggles` with `members.read` → exchange for org X → read org X members → rotate secrets (add, retire old, exchange with new works) → revoke service → exchange 401.

- [ ] **Step 2: Reference docs**

`updating-reference-docs` on the auth/tokens doc (three `kind`s, `system_permissions` claim, no-refresh rule); `creating-reference-docs` for **Admin, impersonation, and service tokens**: system-role matrix, bootstrap runbook (env var, verified user, idempotent, post-wipe repeatability), impersonation semantics (read-only suffix rule, real `sub`, audit), the service registry (dual-secret rotation runbook, exchange contract, one-credential-many-orgs), audit action codes, the 404-hiding rule for `/admin/**`, the uniform exchange 401.

- [ ] **Step 3: Gates + PR**

```bash
./mvnw verify && yamllint --strict -c .yamllint.yml . && npx markdownlint-cli2 && python3 docs/tooling/references_cli.py validate
git push -u origin <ISSUE>-admin-tokens
gh pr create --title "#<ISSUE> feat: system admin roles, impersonation, and service tokens" --label minor --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-07-25-admin-machine-tokens-design.md — the admin domain with seeded system roles and audit log, idempotent super-admin bootstrap, read-only impersonation tokens, and the dual-secret service registry with the kind=service exchange.

Closes #<ISSUE>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review (completed at authoring)

- **Spec coverage:** data model → 1–2, 6; endpoints table → 5 (roles/users/audit), 6 (services + secrets), 7 (impersonation + exchange); claims/bootstrap → 3–4; no-refresh + negative space → 7's tests; audit-not-per-exchange → 6 (`lastUsedAt`); 404-hiding → 5; docs/runbooks → 8.
- **Placeholders:** none; `<ISSUE>` per Task 0.
- **Type consistency:** `TokenClaims` is the single mint input from Task 3 on (Tasks 7 uses it for both kinds); `TokenKinds` constants match the spec's `kind` values; `PermissionCatalog.readOnlyOrgPermissions()` is the one impersonation source; `ServiceGrant` produced in Task 6 and consumed in Task 7.

## Amendment — spec 2 as implemented (2026-08-02)

Written before spec 2's implementation (PR #33) settled. These deltas
override the tasks above where they conflict:

- **Task 3's `record TokenClaims` collides with an existing class.**
  `auth/services/TokenClaims.java` already holds the claim-name constants
  (`ORGANIZATION`, `KIND`) that `JwtService` and `SecurityConfig` read.
  Rename the planned record or fold the constants into it deliberately — do
  not shadow the name.
- **Task 3's converter change must start enforcing `kind`.** Today the
  converter accepts any value, including a missing claim, because only
  `user` exists. The moment three kinds are minted, the converter must
  reject unknown or missing kinds, and human-only checks must read the
  principal's kind.
- **Task 7's exchange follows spec 2's stored-token idioms:** SHA-256 hash
  lookup compared with `MessageDigest.isEqual` (see
  `RefreshTokenService.findByHash`), and a per-client-IP throttle through
  `AuthController`'s `requireCapacity`/`ClientIpResolver` idiom — the
  planned `zarlania.throttle.service-limit` slots into `ThrottleProperties`
  beside the existing limits.
- **Audit-log candidate from spec 2:** `RefreshTokenService.rotate` logs
  refresh-token replays with the `REFRESH_TOKEN_REUSE` WARN marker; consider
  an `auth.refresh-reuse` audit action when the audit log lands, rather than
  leaving the log line as the only trace.
