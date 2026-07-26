# General Orgs, Roles & Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-25-general-orgs-roles-permissions-design.md`
**Depends on:** spec 2's implementation merged to master.

**Goal:** General organizations with invitation-based membership, seeded OWNER/ADMIN/MEMBER roles resolved into a JWT `permissions` claim, `@PreAuthorize` enforcement, and org-switch token minting.

**Architecture:** Roles and permissions live in data (`roles` + `role_permissions`, org-scoped-nullable for future custom roles); `organization_memberships.is_owner` is replaced by `role_id` with a backfill migration. Minting resolves role→permissions once per token; Spring Security turns the claim into authorities. Content-dependent guards (owner-only owner-changes, last-owner, personal-immutable) are service-level.

**Tech Stack:** unchanged from plan 2.

## Global Constraints

- Plans 1–2 Global Constraints apply (wrapper, spotless, gates, commit format `#<ISSUE> <type>: …`, `Clock`-only time, ProblemDetail error contract).
- Permission strings (exact): `org.manage`, `members.manage`, `members.read`. Role names (exact): `OWNER`, `ADMIN`, `MEMBER`. Role→permission matrix: OWNER = all three; ADMIN = `members.manage`, `members.read`; MEMBER = `members.read`.
- New error codes: `orgs.name-taken` (409), `orgs.quota-exceeded` (409), `orgs.last-owner` (409), `orgs.personal-immutable` (409), `orgs.not-found` (404), `invitations.already-pending` (409), `invitations.expired` (400), `invitations.not-found` (404).
- Existence-hiding: any org-scoped request where the caller is not a member of `{orgId}` — or where the token's `org` claim ≠ `{orgId}` — returns 404 `orgs.not-found`, never 403.
- Invariants: general orgs ≥ 1 OWNER always; personal orgs reject every membership-mutating operation.
- Invitation expiry: 14 days. Org-creation quota: `zarlania.orgs.max-owned-general`, default 10.
- Deviation, stated once: `role_permissions` is a pure association table (no `id`/timestamps) — an element collection has no row identity; the reference doc records this exception to the standard-columns rule.

---

### Task 0: Tracking issue and branch

- [ ] **Step 1: Issue (feature template shape, as in plan 2 Task 0)**

```bash
gh issue create --title "feat: general organizations with roles, permissions, and invitations" --label feature --body "$(cat <<'EOF'
### Problem

Users can only ever act in their personal organization; there is no way to collaborate, invite anyone, or hold a role.

### Proposed solution

Implement docs/superpowers/specs/2026-07-25-general-orgs-roles-permissions-design.md: seeded OWNER/ADMIN/MEMBER roles in data, invitation-based membership, member management with owner invariants, permissions claim + @PreAuthorize enforcement, and org-switch token minting.

### Alternatives considered

Custom roles from day one; direct member add without consent — rejected in the spec's decisions log.

### Is this a breaking change?

No — backwards compatible

### Additional context

Spec 3 of 7.

### Before submitting

- [x] I searched existing issues and discussions and this is not a duplicate.
- [x] I agree to follow this project's Code of Conduct.
EOF
)"
git fetch origin master && git checkout -b <ISSUE>-orgs-roles origin/master
```

---

### Task 1: Migration V2 — roles, invitations, membership backfill

**Files:**
- Create: `src/main/resources/db/migration/V2__roles_permissions_invitations.sql`
- Test: `src/test/java/com/zarlania/api/organizations/MigrationV2BackfillTest.java`

**Interfaces:** Produces the schema below. The fixed role UUIDs are load-bearing constants reused by the backfill and by nothing else (code looks roles up by name).

- [ ] **Step 1: Failing backfill test**

`MigrationV2BackfillTest` — its own container (Task 3 pattern from plan 2) but **no** `@SpringBootTest`; it drives Flyway directly so it can inject spec-2-shaped data between versions:

```java
@Testcontainers
class MigrationV2BackfillTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  @Test
  void backfillMapsOwnerFlagToRoles() throws Exception {
    flyway("1").migrate();
    try (Connection c =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      c.createStatement()
          .execute(
              """
              INSERT INTO users (id, email, username, created_at, updated_at)
              VALUES ('a0000000-0000-0000-0000-00000000000a', 'o@x.com', 'owner-user', now(), now()),
                     ('b0000000-0000-0000-0000-00000000000b', 'm@x.com', 'member-user', now(), now());
              INSERT INTO organizations (id, name, type, created_at, updated_at)
              VALUES ('c0000000-0000-0000-0000-00000000000c', 'backfill-org', 'GENERAL', now(), now());
              INSERT INTO organization_memberships (id, organization_id, user_id, is_owner, created_at, updated_at)
              VALUES (gen_random_uuid(), 'c0000000-0000-0000-0000-00000000000c', 'a0000000-0000-0000-0000-00000000000a', true, now(), now()),
                     (gen_random_uuid(), 'c0000000-0000-0000-0000-00000000000c', 'b0000000-0000-0000-0000-00000000000b', false, now(), now());
              """);
      flyway("2").migrate();
      var rs =
          c.createStatement()
              .executeQuery(
                  """
                  SELECT r.name FROM organization_memberships m
                  JOIN roles r ON r.id = m.role_id
                  JOIN users u ON u.id = m.user_id ORDER BY u.username
                  """);
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("MEMBER"); // member-user
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("OWNER"); // owner-user
    }
  }
}
```

Add a second test `seedsBuiltInRolesAndPermissions`: after `flyway("2").migrate()` on a fresh… (the class-level container persists — order-independent: run the seed assertions inside the same method after backfill, or use `count(*)` checks tolerant of the shared container; simplest: assert in the same test method after the role join — `SELECT count(*) FROM role_permissions` = 6 and the OWNER role has 3 rows). Run → FAIL (V2 missing).

- [ ] **Step 2: The migration**

`V2__roles_permissions_invitations.sql`:

```sql
CREATE TABLE roles (
    id              uuid PRIMARY KEY,
    organization_id uuid REFERENCES organizations (id),
    name            text NOT NULL,
    created_at      timestamptz(6) NOT NULL,
    updated_at      timestamptz(6) NOT NULL,
    UNIQUE (organization_id, name)
);
-- Built-in roles have NULL organization_id; NULLs are distinct in a UNIQUE
-- constraint, so built-in name uniqueness needs a partial index.
CREATE UNIQUE INDEX uq_roles_builtin_name ON roles (name) WHERE organization_id IS NULL;

-- Association table: no row identity, so no id/audit columns (documented exception).
CREATE TABLE role_permissions (
    role_id    uuid NOT NULL REFERENCES roles (id),
    permission text NOT NULL,
    PRIMARY KEY (role_id, permission)
);

INSERT INTO roles (id, organization_id, name, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'OWNER',  now(), now()),
    ('00000000-0000-0000-0000-000000000002', NULL, 'ADMIN',  now(), now()),
    ('00000000-0000-0000-0000-000000000003', NULL, 'MEMBER', now(), now());

INSERT INTO role_permissions (role_id, permission) VALUES
    ('00000000-0000-0000-0000-000000000001', 'org.manage'),
    ('00000000-0000-0000-0000-000000000001', 'members.manage'),
    ('00000000-0000-0000-0000-000000000001', 'members.read'),
    ('00000000-0000-0000-0000-000000000002', 'members.manage'),
    ('00000000-0000-0000-0000-000000000002', 'members.read'),
    ('00000000-0000-0000-0000-000000000003', 'members.read');

ALTER TABLE organization_memberships ADD COLUMN role_id uuid REFERENCES roles (id);
UPDATE organization_memberships
SET role_id = CASE
    WHEN is_owner THEN '00000000-0000-0000-0000-000000000001'::uuid
    ELSE '00000000-0000-0000-0000-000000000003'::uuid
END;
ALTER TABLE organization_memberships ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE organization_memberships DROP COLUMN is_owner;

CREATE TABLE organization_invitations (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    inviter_user_id uuid NOT NULL REFERENCES users (id),
    invitee_user_id uuid NOT NULL REFERENCES users (id),
    role_id         uuid NOT NULL REFERENCES roles (id),
    status          text NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED')),
    expires_at      timestamptz(6) NOT NULL,
    created_at      timestamptz(6) NOT NULL,
    updated_at      timestamptz(6) NOT NULL
);
CREATE UNIQUE INDEX uq_invitations_pending
    ON organization_invitations (organization_id, invitee_user_id) WHERE status = 'PENDING';
CREATE INDEX idx_invitations_invitee ON organization_invitations (invitee_user_id);
```

- [ ] **Step 3: Update the spec-2 entities that V2 breaks, run everything, commit**

`Membership`: replace `boolean owner` with an in-domain relation `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "role_id") private Role role;` (constructor becomes `Membership(Organization organization, UUID userId, Role role)`; `getRole()`; delete `isOwner()`). Compile errors point at every caller: `OrganizationService.createPersonalOrganization` now looks up the built-in OWNER role (Task 2's `RoleRepository.findBuiltIn("OWNER")`) — implement Task 2's `Role` entity + repository *now* as part of this step (they are inseparable from compiling): 

`Role.java` (organizations/entities):

```java
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

  @Column(name = "organization_id")
  private UUID organizationId; // null = built-in; plain id, orgs may own custom roles later

  @Column(nullable = false)
  private String name;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
  @Column(name = "permission", nullable = false)
  private Set<String> permissions = new HashSet<>();

  protected Role() {}

  public String getName() {
    return name;
  }

  public Set<String> getPermissions() {
    return Set.copyOf(permissions);
  }
}
```

`RoleRepository.java` (organizations/repositories):

```java
public interface RoleRepository extends JpaRepository<Role, UUID> {
  Optional<Role> findByOrganizationIdIsNullAndName(String name);
}
```

`RoleNames.java` (organizations/services): `public final class RoleNames { public static final String OWNER = "OWNER"; public static final String ADMIN = "ADMIN"; public static final String MEMBER = "MEMBER"; private RoleNames() {} }` and `Permissions.java` (common/security — spec 4 reuses it): constants `ORG_MANAGE = "org.manage"`, `MEMBERS_MANAGE = "members.manage"`, `MEMBERS_READ = "members.read"`.

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/resources/db/migration src/main/java/com/zarlania/api/organizations src/main/java/com/zarlania/api/common src/test/java
git commit -m "#<ISSUE> feat: add roles, permissions, and invitations schema with owner backfill"
```

---

### Task 2: Permission resolution and the permissions claim

**Files:**
- Modify: `src/main/java/com/zarlania/api/organizations/services/OrganizationService.java`
- Modify: `src/main/java/com/zarlania/api/organizations/dtos/` — add `MembershipDto.java`
- Modify: `src/main/java/com/zarlania/api/auth/services/JwtService.java`
- Modify: `src/main/java/com/zarlania/api/auth/services/AuthTokenService.java`
- Modify: `src/main/java/com/zarlania/api/auth/SecurityConfig.java`
- Test: extend `JwtServiceTest`, `OrganizationServiceTest`

**Interfaces:**
- Produces: `MembershipDto(UUID userId, UUID organizationId, String role, Set<String> permissions, Instant joinedAt)`; `OrganizationService.membershipOf(UUID userId, UUID organizationId) → Optional<MembershipDto>`; `JwtService.mint(UUID userId, UUID organizationId, String kind, String role, Collection<String> permissions)` — claims gain `"role"` (string) and `"permissions"` (string array). The security converter maps each `permissions` entry to a `SimpleGrantedAuthority` of the same string, enabling `@PreAuthorize("hasAuthority('members.manage')")`.

- [ ] **Step 1: Failing tests**

Extend `JwtServiceTest`: minted token carries `role` and `permissions` claims verbatim. Extend `OrganizationServiceTest`: `membershipOf` for a personal-org owner returns role `OWNER` with all three permissions; empty for a non-member.

- [ ] **Step 2: Implement**

`OrganizationService.membershipOf`: `memberships.findByUserIdAndOrganizationId(userId, orgId)` (add to `MembershipRepository`: `Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);`) mapped to the DTO (`joinedAt` = `getCreatedAt()`). `JwtService.mint` adds `.claim("role", role).claim("permissions", List.copyOf(permissions))`. `AuthTokenService.mint(userId, organizationId)` now resolves `membershipOf` and passes role+permissions (orElseThrow → the caller guaranteed membership). `SecurityConfig.authConverter()` builds `List<SimpleGrantedAuthority>` from `jwt.getClaimAsStringList("permissions")` (null-safe: default empty) and passes it as the token's authorities.

- [ ] **Step 3: Run all tests → commit**

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: resolve role permissions into JWT claims and authorities"
```

---

### Task 3: Create/list orgs and exact user lookup

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/controllers/OrganizationController.java`
- Create: `src/main/java/com/zarlania/api/organizations/dtos/CreateOrganizationRequest.java`, `MyOrganizationDto.java`
- Modify: `src/main/java/com/zarlania/api/organizations/services/OrganizationService.java`
- Modify: `src/main/java/com/zarlania/api/users/controllers/UserController.java`
- Create: `src/main/java/com/zarlania/api/users/dtos/UserLookupDto.java`
- Create: `src/main/java/com/zarlania/api/organizations/OrgsProperties.java`
- Test: `src/test/java/com/zarlania/api/organizations/controllers/OrganizationControllerTest.java`

**Interfaces:**
- Produces: `POST /organizations` `{name}` → 201 `OrganizationDto` (creator = OWNER; general type); `GET /organizations` → list of `MyOrganizationDto(UUID id, String name, OrganizationType type, String role)`; `GET /users?username=x` → list of 0–1 `UserLookupDto(UUID id, String username)` (400 without the parameter). Service methods: `OrganizationDto createGeneralOrganization(UUID creatorUserId, String name)` (name-taken → `orgs.name-taken`; quota → `orgs.quota-exceeded`), `List<MyOrganizationDto> organizationsOf(UUID userId)`. `OrgsProperties(int maxOwnedGeneral)` prefix `zarlania.orgs`, default 10 (YAML: `zarlania.orgs.max-owned-general: 10`).

- [ ] **Step 1: Failing controller test**

Integration harness (container + MockMvc + a helper that registers/verifies/logs-in a user — extract the plan-2 journey helpers into `src/test/java/com/zarlania/api/testsupport/AuthTestHelper.java` here, a component with `record Session(UUID userId, String accessToken, String refreshCookie)` and `Session registerVerifyLogin(String email, String username)`): create org → 201, appears in `GET /organizations` with role OWNER alongside the personal org; duplicate name → 409 `orgs.name-taken` (case-insensitive: create "MyGuild", attempt "myguild"); 11th owned general org → 409 `orgs.quota-exceeded`; `GET /users?username=` exact match returns the one user, near-miss (prefix) returns empty list, missing param → 400.

- [ ] **Step 2: Implement**

`createGeneralOrganization`: quota check via new `MembershipRepository` method `long countByUserIdAndRoleNameAndOrganizationType(...)` — implement as a `@Query`:

```java
@Query(
    """
    select count(m) from Membership m
    where m.userId = :userId and m.role.name = 'OWNER'
      and m.organization.type = com.zarlania.api.organizations.entities.OrganizationType.GENERAL
    """)
long countGeneralOrgsOwnedBy(UUID userId);
```

Name conflict: catch `DataIntegrityViolationException` from the unique index → `ApiException(ErrorCode.ORG_NAME_TAKEN, …)` (add the new codes to `ErrorCode` exactly as listed in Global Constraints). Lookup endpoint on `UserController`: `@GetMapping("/users") List<UserLookupDto> lookup(@RequestParam String username)` → `userService.findByIdentifier` filtered to username match… No: exact behavior is *username only* — add `UserService.lookupByUsername(String username) → Optional<UserLookupDto>`-shaped method using `users.findByUsername`, returning `List.of()` or a singleton list.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=OrganizationControllerTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api src/main/resources/application.yml
git commit -m "#<ISSUE> feat: create and list general organizations with exact user lookup"
```

---

### Task 4: The org guard and the members endpoint

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/services/OrgAccessGuard.java`
- Modify: `src/main/java/com/zarlania/api/organizations/controllers/OrganizationController.java`
- Create: `src/main/java/com/zarlania/api/organizations/dtos/MemberDto.java`
- Test: extend `OrganizationControllerTest`

**Interfaces:**
- Produces: `OrgAccessGuard.requireOrgScope(AuthPrincipal principal, UUID orgId)` — throws `ApiException(ORG_NOT_FOUND)` (404) when `!principal.organizationId().equals(orgId)`; every `/organizations/{id}/**` handler calls it first. `GET /organizations/{id}/members` — `@PreAuthorize("hasAuthority('members.read')")` → list of `MemberDto(UUID userId, String username, String role, Instant joinedAt)` (usernames via `UserService.findById`, batched: add `UserService.findAllByIds(Collection<UUID>) → Map<UUID, UserDto>` backed by `users.findAllById`).

- [ ] **Step 1: Failing tests**

Extend the harness: member list for own org with token scoped to it → 200 with both members and roles; **token scoped to org A calling org B's member list → 404 `orgs.not-found`** (the cross-org rule — even for a user who is a member of both, since the token is single-org); a MEMBER-role token still reads (has `members.read`); an outsider's token (member of neither) → 404.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=OrganizationControllerTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: org-scoped member listing behind the existence-hiding guard"
```

---

### Task 5: Invitations

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/entities/Invitation.java`, `InvitationStatus.java`
- Create: `src/main/java/com/zarlania/api/organizations/repositories/InvitationRepository.java`
- Create: `src/main/java/com/zarlania/api/organizations/services/InvitationService.java`
- Create: `src/main/java/com/zarlania/api/organizations/controllers/InvitationController.java`
- Create: `src/main/java/com/zarlania/api/organizations/dtos/InviteRequest.java`, `InvitationDto.java`
- Test: `src/test/java/com/zarlania/api/organizations/controllers/InvitationFlowTest.java`

**Interfaces:**
- Produces:
  - `Invitation` extends `BaseEntity`: in-domain relations `organization` + `role` (`@ManyToOne` lazy), plain ids `inviterUserId`/`inviteeUserId`, `@Enumerated(STRING) InvitationStatus status`, `Instant expiresAt`; methods `boolean isPending(Instant now)` (`status == PENDING && now.isBefore(expiresAt)`), `void accept/decline/revoke(Instant at)` (each asserts current status PENDING, sets the new status).
  - Endpoints: `POST /organizations/{id}/invitations` `InviteRequest(UUID inviteeUserId, String role)` — `@PreAuthorize("hasAuthority('members.manage')")` + guard; offering OWNER requires caller's role == OWNER (service check → `ApiException(ORG_LAST_OWNER…` no — use a dedicated message under `orgs.not-found`? No: spec says owner-only offering is a content-dependent guard; failing it is a 404? It is an authorization failure *inside* the org → use 403-style... The spec's only 403s are permission failures; keep it as `ApiException(ErrorCode.VALIDATION_FAILED…`? No. **Decision (spec-consistent):** reuse `orgs.last-owner`'s family: add code `orgs.owner-required` (403) to `ErrorCode` — used by both invite-as-OWNER and Task 6's promote/demote paths.)
  - `DELETE /organizations/{id}/invitations/{invId}` (revoke, `members.manage`, guard) → 204; `GET /invitations` (invitee's own pending, any authenticated token) → list of `InvitationDto(UUID id, UUID organizationId, String organizationName, String role, String inviterUsername, Instant expiresAt)`; `POST /invitations/{id}/accept` → 200 (creates membership with the offered role); `POST /invitations/{id}/decline` → 204. Accept/decline require the caller's `userId` == invitee (else 404 `invitations.not-found`); expired → 400 `invitations.expired`; duplicate pending invite → 409 `invitations.already-pending`; invitee already a member → 409 `invitations.already-pending` (same code, message "already a member").

- [ ] **Step 1: Failing flow test**

Two users via `AuthTestHelper`; owner creates org, invites the second as ADMIN → 201; duplicate invite → 409; invitee `GET /invitations` (with their *personal* token — invitations are user-level, not org-scoped) shows it with org name and inviter username; accept → member list now shows ADMIN; an ADMIN invites a third user as OWNER → 403 `orgs.owner-required`; the owner invites-as-OWNER → allowed; decline path; revoke path (revoked invitation's accept → 404 `invitations.not-found`); expiry: create invitation, `UPDATE organization_invitations SET expires_at = now() - interval '1 day'` via `jdbcTemplate`, accept → 400 `invitations.expired`; personal org invite attempt → 409 `orgs.personal-immutable`.

- [ ] **Step 2: Implement, pass, commit**

`InvitationService` methods: `InvitationDto invite(AuthPrincipal caller, UUID orgId, UUID inviteeUserId, String roleName)`, `List<InvitationDto> pendingFor(UUID userId)`, `void accept(UUID userId, UUID invitationId)` (creates `Membership` with the invitation's role), `void decline(UUID userId, UUID invitationId)`, `void revoke(AuthPrincipal caller, UUID orgId, UUID invitationId)`. Personal-immutable check: org type PERSONAL → `ApiException(ORG_PERSONAL_IMMUTABLE)` — this same check guards every mutating path in Task 6 (put it in `OrgAccessGuard.requireGeneralOrg(Organization org)`).

```bash
./mvnw test -Dtest=InvitationFlowTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: invitation-based membership with consent and expiry"
```

---

### Task 6: Member management — role changes, removal, leaving

**Files:**
- Modify: `src/main/java/com/zarlania/api/organizations/controllers/OrganizationController.java`
- Modify: `src/main/java/com/zarlania/api/organizations/services/OrganizationService.java`
- Create: `src/main/java/com/zarlania/api/organizations/dtos/ChangeRoleRequest.java`
- Test: `src/test/java/com/zarlania/api/organizations/controllers/MemberManagementTest.java`

**Interfaces:**
- Produces: `PATCH /organizations/{id}/members/{userId}` `ChangeRoleRequest(String role)` (`members.manage`; promoting to OWNER or demoting an OWNER requires caller role OWNER → else `orgs.owner-required`; last-owner guard → `orgs.last-owner`) → 200 `MemberDto`; `DELETE /organizations/{id}/members/{userId}` (`members.manage`; removing an OWNER requires OWNER; last-owner guard) → 204; `DELETE /organizations/{id}/members/me` (any member; last-owner guard) → 204. Service: `MemberDto changeRole(AuthPrincipal caller, UUID orgId, UUID targetUserId, String newRole)`, `void removeMember(AuthPrincipal caller, UUID orgId, UUID targetUserId)`, `void leave(AuthPrincipal caller, UUID orgId)`. Last-owner rule: an operation that would leave a GENERAL org with zero OWNER memberships fails (`countByOrganizationIdAndRoleName` query: `select count(m) from Membership m where m.organization.id = :orgId and m.role.name = 'OWNER'`).

- [ ] **Step 1: Failing test — the invariant gauntlet**

Owner + ADMIN + MEMBER in one org (via Task 5 flows in the helper). Cases: ADMIN promotes MEMBER→ADMIN ok; ADMIN promotes anyone→OWNER → 403 `orgs.owner-required`; OWNER promotes ADMIN→OWNER ok; OWNER demotes the other OWNER ok; demoting the **last** OWNER → 409 `orgs.last-owner`; sole owner leaving → 409 `orgs.last-owner`; after promoting a second owner the original leaves ok; ADMIN removes MEMBER ok; ADMIN removes an OWNER → 403 `orgs.owner-required`; MEMBER hits PATCH → 403 (no `members.manage` authority — plain Spring Security 403); every path against the personal org → 409 `orgs.personal-immutable`; unknown target user in the org → 404 `orgs.not-found`.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=MemberManagementTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: member role changes and removal under owner invariants"
```

---

### Task 7: Org switching — `POST /auth/token`

**Files:**
- Modify: `src/main/java/com/zarlania/api/auth/controllers/AuthController.java`
- Modify: `src/main/java/com/zarlania/api/auth/services/AuthTokenService.java`
- Create: `src/main/java/com/zarlania/api/auth/dtos/SwitchOrgRequest.java`
- Test: `src/test/java/com/zarlania/api/auth/controllers/OrgSwitchTest.java`

**Interfaces:**
- Produces: `POST /auth/token` `SwitchOrgRequest(UUID organizationId)` — requires a valid refresh cookie (it is under `/auth`, so the cookie flows). Behavior: rotate-validate the cookie's family to authenticate the caller (reuse `RefreshTokenService.rotate` — a failed rotation is the same 401), then verify membership in the target org (`membershipOf`; absent → 404 `orgs.not-found`), then **revoke the just-rotated family** and start a fresh family + access token scoped to the target org. Response shape identical to login (`TokenResponse` + new cookie). `AuthTokenService.switchOrganization(String rawRefreshCookie, UUID targetOrgId) → MintedSession`.

- [ ] **Step 1: Failing test**

User in personal org + a general org (helper): switch with valid cookie → 200; new access token's `org` claim = target, `role`/`permissions` = the membership's; old cookie dead (family revoked) but new cookie refreshes fine; switch to an org the user does not belong to → 404 and the *current* family is NOT revoked beyond its normal rotation (assert: after the 404, the returned-nothing means the rotated cookie from the failed attempt… — the rotation happened before the membership check, so assert the *newest* cookie still works: the implementation must rotate, check membership, and on failure return 404 **with** the rotated cookie set, keeping the session alive as the spec's UX demands); switch without cookie → 401.

- [ ] **Step 2: Implement**

```java
public record SwitchOutcome(MintedSession session) {}

public MintedSession switchOrganization(String rawRefreshCookie, UUID targetOrgId) {
  RefreshRotation rotation = rotateOr401(rawRefreshCookie);
  MembershipDto membership =
      organizationService
          .membershipOf(rotation.userId(), targetOrgId)
          .orElseThrow(
              () -> new OrgSwitchDeniedException(rotation)); // carries the rotated token
  refreshTokenService.revokeFamilyOf(rotation.newRaw());
  IssuedRefreshToken fresh = refreshTokenService.startFamily(rotation.userId(), targetOrgId);
  return new MintedSession(
      jwtService.mint(
          rotation.userId(), targetOrgId, TokenKinds.USER,
          membership.role(), membership.permissions()),
      fresh);
}
```

`OrgSwitchDeniedException` (auth/services) carries the `RefreshRotation`; the controller catches it, sets the rotated cookie, and rethrows `ApiException(ORG_NOT_FOUND)` so the 404 body still carries the code while the session survives.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=OrgSwitchTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: mint org-scoped sessions via POST /auth/token"
```

---

### Task 8: Journey test, docs, PR

**Files:**
- Test: `src/test/java/com/zarlania/api/OrgsJourneyTest.java`
- Reference docs via skills; `render.yaml`/`.env.example` unchanged (no new env).

- [ ] **Step 1: Journey**

One method: two users register/verify/login → user A creates "dragons-hoard" → invites B as MEMBER → B accepts with personal token → B switches into the org → B reads members (200) but cannot invite (403) → A promotes B to ADMIN → B (after re-switching — stale claims are the documented trade-off) invites user C → C accepts → A promotes B to OWNER (A is OWNER) → A leaves → B is the last owner; B leaving → 409 `orgs.last-owner`.

- [ ] **Step 2: Reference docs**

`updating-reference-docs` on the auth doc (claims now include `role`/`permissions`; `POST /auth/token`); `creating-reference-docs` for **Organizations, roles, and permissions**: the role/permission matrix, invariants (≥1 owner general, ==1 personal, personal-immutable), invitation lifecycle + expiry, quota, the 404-existence-hiding rule, the `role_permissions` association-table exception, and the ≤15-min claim-staleness trade-off.

- [ ] **Step 3: Gates + PR**

```bash
./mvnw verify && yamllint --strict -c .yamllint.yml . && npx markdownlint-cli2 && python3 docs/tooling/references_cli.py validate
git push -u origin <ISSUE>-orgs-roles
gh pr create --title "#<ISSUE> feat: general organizations with roles and invitations" --label minor --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-07-25-general-orgs-roles-permissions-design.md — roles/permissions in data with the owner backfill, invitation-based membership, member management under owner invariants, permissions claims with @PreAuthorize enforcement, and org-switch minting.

Closes #<ISSUE>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review (completed at authoring)

- **Spec coverage:** every endpoint row in the spec's table maps to Tasks 3–7; roles/matrix/seeds → 1; claims + enforcement + staleness → 2; invariants → 5–6; 404 semantics → 4 (guard) and 6–7 (cases); quota + naming → 3; backfill test → 1; journey → 8. Custom-role readiness = nullable `organization_id` on `roles`, no endpoints (spec deferral).
- **Placeholders:** none; the one in-flight design question (invite-as-OWNER failure code) is resolved inline as `orgs.owner-required` (403), added to the Global Constraints codes.
- **Type consistency:** `MembershipDto`/`MemberDto` fields align between Tasks 2, 4, 6; `RefreshRotation` reused from plan 2 Task 10 unchanged; `mint(...)` five-arg signature consistent across Tasks 2 and 7; `RoleNames`/`Permissions` constants single-homed.
