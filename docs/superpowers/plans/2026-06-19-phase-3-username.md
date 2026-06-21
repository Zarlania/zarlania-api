# Phase 3 — `users` handle: replace `displayName` with a unique `username` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the user's free-form `displayName` with a globally-unique `username` handle, so a personal organization (named after that handle) is itself globally unique — closing the one-personal-org-per-user assumption Phase 2 relied on.

**Architecture:** Schema is owned by Flyway; a V3 migration drops `users.display_name` and adds a `NOT NULL`, `UNIQUE` `users.username`. The `users` domain renames the field across entity/DTO/mapper/service and surfaces the new uniqueness violation as a domain exception mirroring the existing email pattern. The change is scoped to the `users` domain: the `organizations` domain is left untouched and keeps enforcing one-personal-org-per-user via its existing owner-id + org-type service pre-check (per the user's decision; the spec's optional step 4 retirement is deliberately not taken).

**Tech Stack:** Java 25, Spring Boot 4.1.x, Maven (`./mvnw`), Spring Data JPA over H2 (in-memory), Flyway migrations, Lombok (entities only), JUnit 5 + AssertJ + Mockito.

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 25 / Spring Boot 4.1.x / Maven wrapper `./mvnw`.** Do not add dependencies.
- **Flyway owns the schema; Hibernate runs `ddl-auto=validate`.** Never edit a released migration (`V1`, `V2`) — only add new `V{n}__*.sql` files. The mapped entity must match the migrated schema or the app fails to boot.
- **Lombok on entities only** (`@Getter`/`@Setter`/`@NoArgsConstructor`); **never `@Data`/`@EqualsAndHashCode` on a JPA entity**. DTOs are Java `records` with no Lombok.
- **Every persisted entity extends `Auditable`** (already true for `UserEntity`).
- **Domain boundary (ADR-0011):** `organizations` references a user only by opaque `userId`; it never imports or loads the `users` entity. The only `users`↔`organizations` link is the DB FK `memberships.user_id` → `users.id`.
- **DTO carries the canonical domain name** (`User`); the entity is suffixed (`UserEntity`).
- **≥ 80% line/branch JaCoCo gate** plus Spotless (google-java-format), Checkstyle, SpotBugs all run in the build. Fix root causes; never silence a gate. Lombok-generated code is already excluded via `lombok.config`.
- **Full build gate:** `./mvnw verify` must pass at the end of every task before committing.
- **Process:** work on a branch `feat/43-phase-3-username` tied to a Phase 3 GitHub issue; the PR title references `#43`; bump the version inside the PR (Task 5).

---

## File Structure

**Created:**
- `src/main/resources/db/migration/V3__replace_user_display_name_with_username.sql` — drop `display_name`, add unique `username`.
- `src/main/java/com/zarlania/api/users/exception/UsernameAlreadyExistsException.java` — domain exception for a taken handle.
- `src/main/resources/db/migration/V4__drop_memberships_user_role_index.sql` — drop the now-dead index that backed the retired pre-check (Task 4).

**Modified — `users` domain (main):**
- `users/entity/UserEntity.java` — `displayName` → `username`.
- `users/dto/User.java` — `displayName` → `username`.
- `users/service/UserMapper.java` — map `username`.
- `users/repository/UserRepository.java` — add `existsByUsername`.
- `users/service/UserService.java` — rename param, add username pre-check + constraint mapping (parameterized helper).

**Modified — `organizations` domain (main, Task 4):**
- `organizations/service/OrganizationService.java` — remove personal-org pre-check.
- `organizations/repository/MembershipRepository.java` — remove `existsByUserIdAndRoleAndOrganizationType`.
- **Delete** `organizations/exception/PersonalOrganizationAlreadyExistsException.java`.

**Modified — tests:**
- `users/.../UserRepositoryTest`, `UserServiceTest`, `UserServiceUnitTest`, `UserMapperTest` — `displayName` → `username`, plus new uniqueness tests.
- `organizations/support/OrganizationTestSupport` — seed `username` instead of `display_name`.
- `organizations/service/OrganizationServiceTest`, `organizations/repository/MembershipRepositoryTest` — drop the retired-behavior tests, add the DB-backed personal-org-name test (Task 4).

**Modified — docs (Task 5):**
- `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md` — flip the Phase 3 status row to Done (the spec instructs this on each phase landing).
- `pom.xml` — version bump.

---

### Task 0: Setup — issue and branch

- [ ] **Step 1: Create the Phase 3 issue and branch**

If no Phase 3 issue exists yet, create one, then branch from `master`:

```bash
gh issue create \
  --title "Phase 3: replace user displayName with a unique username" \
  --body "Implements Phase 3 of docs/superpowers/specs/2026-06-17-users-and-organizations-design.md"
# note the issue number it prints, then:
git checkout master && git pull
git checkout -b feat/43-phase-3-username
```

Use the printed `43` in the branch name and later in the PR title.

---

### Task 1: Rename `displayName` → `username` (schema + structural rename)

Pure structural rename — no new uniqueness behavior yet. The migration and the entity must change together: with `ddl-auto=validate`, an entity that names `display_name` against a schema that no longer has it fails to boot, so this whole rename is one green increment.

**Files:**
- Create: `src/main/resources/db/migration/V3__replace_user_display_name_with_username.sql`
- Modify: `src/main/java/com/zarlania/api/users/entity/UserEntity.java`
- Modify: `src/main/java/com/zarlania/api/users/dto/User.java`
- Modify: `src/main/java/com/zarlania/api/users/service/UserMapper.java`
- Modify: `src/main/java/com/zarlania/api/users/service/UserService.java`
- Modify (tests): `UserMapperTest`, `UserRepositoryTest`, `UserServiceTest`, `UserServiceUnitTest`
- Modify (cross-domain test seed): `src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java`

**Interfaces:**
- Produces: `UserEntity.getUsername()/setUsername(String)`; `User(UUID id, String email, String username)`; `UserService.create(String email, String username)` returning `User`.

- [ ] **Step 1: Update the tests to use `username` (red — won't compile)**

In `src/test/java/com/zarlania/api/users/service/UserMapperTest.java`, replace the `setDisplayName`/`displayName()` lines:

```java
    entity.setEmail("linus@example.com");
    entity.setUsername("linus");

    User dto = new UserMapper().toDto(entity);

    assertThat(dto.id()).isEqualTo(expectedId);
    assertThat(dto.email()).isEqualTo("linus@example.com");
    assertThat(dto.username()).isEqualTo("linus");
```

In `src/test/java/com/zarlania/api/users/repository/UserRepositoryTest.java`, replace every `user.setDisplayName("X")` with a distinct `user.setUsername("<handle>")` (handles must be unique per saved row since the column is now unique). Use:
- `savingAssignsIdAndAuditTimestamps`: `user.setUsername("ada");`
- `findByEmailReturnsTheSavedUser`: `user.setUsername("grace");`
- `existsByEmailReflectsPersistedState`: `user.setUsername("nobody");`
- `duplicateEmailViolatesTheUniqueConstraint`: `first.setUsername("dupOne");` and `second.setUsername("dupTwo");` (different handles so only the email constraint is exercised).

In `src/test/java/com/zarlania/api/users/service/UserServiceTest.java`, update the `create(...)` calls and assertions to pass/assert a `username` (second arg) instead of a display name, and rename the two display-name validation tests:

```java
  @Test
  void createPersistsAndReturnsDtoWithId() {
    User created = userService.create("alan@example.com", "alan");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("alan@example.com");
    assertThat(created.username()).isEqualTo("alan");
    assertThat(userService.findById(created.id())).contains(created);
  }
```

For the remaining tests in that file, change the second argument to a handle (`"name"`, `"twinOne"`/`"twinTwo"`, `"maggie"`, etc.) and rename:
- `createRejectsBlankDisplayName` → `createRejectsBlankUsername` (call `userService.create("noname@example.com", "  ")`).
- `createRejectsNullDisplayName` → `createRejectsNullUsername` (call `userService.create("noname@example.com", null)`).
- In `createRejectsDuplicateEmail`, give the two users the **same** email but **different** usernames (`"twinOne"`, `"twinTwo"`) so only the email constraint is hit.

In `src/test/java/com/zarlania/api/users/service/UserServiceUnitTest.java`, update both existing tests so the `create` call passes a username and the "unrelated integrity" message references `USERNAME` instead of `DISPLAY_NAME`, and stub `existsByUsername` (the service will gain that pre-check in Task 2; stub now so strict Mockito stays satisfied across both tasks):

```java
  @Test
  void createTranslatesEmailUniquenessRaceIntoDomainException() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("race@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("racer")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_USERS_EMAIL\"]"));

    assertThatThrownBy(() -> userService.create("race@example.com", "racer"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void createRethrowsIntegrityViolationUnrelatedToUniqueness() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("long@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("waytoolong")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "Value too long for column \"USERNAME VARCHAR(100)\""));

    assertThatThrownBy(() -> userService.create("long@example.com", "waytoolong"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(EmailAlreadyExistsException.class);
  }
```

> NOTE: `existsByUsername` does not exist on the repository until Task 2. These two stubs are harmless in Task 1 only if Task 1 also adds the method — but to keep Task 1 self-contained, add `existsByUsername` to the repository here in Task 1 (it is needed in Task 2 regardless). See Step 3.

In `src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java`, change the seed insert to populate `username` (unique per user — derive from the unique email):

```java
    entityManager
        .createNativeQuery(
            "INSERT INTO users (id, email, username, created_at, updated_at) "
                + "VALUES (?1, ?2, ?3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "seed-" + email)
        .executeUpdate();
```

- [ ] **Step 2: Run the build to confirm it fails to compile**

Run: `./mvnw -q test-compile`
Expected: FAIL — `User` has no `username()`, `UserEntity` has no `setUsername`, etc.

- [ ] **Step 3: Apply the structural rename in main code**

Create `src/main/resources/db/migration/V3__replace_user_display_name_with_username.sql`:

```sql
-- Phase 3: the user's public name becomes a unique handle (username), replacing display_name.
-- A personal organization is created under this handle, so a globally-unique username makes the
-- personal-org name globally unique too (see uq_organizations_name in V2), which DB-enforces the
-- one-personal-organization-per-user rule. The users table is empty at every fresh migration
-- (in-memory H2 now; an empty table at first Postgres baseline later), so the NOT NULL column needs
-- no backfill default.
ALTER TABLE users DROP COLUMN display_name;
ALTER TABLE users ADD COLUMN username VARCHAR(100) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_username UNIQUE (username);
```

In `src/main/java/com/zarlania/api/users/entity/UserEntity.java`, replace the `display_name` field:

```java
  @Setter
  @Column(name = "username", nullable = false, length = 100)
  private String username;
```

In `src/main/java/com/zarlania/api/users/dto/User.java`, replace the record and its javadoc param:

```java
/**
 * Immutable view of a user for use across the domain boundary and in API responses. This DTO — not
 * the JPA {@code UserEntity} — is the type passed throughout the application; mapping from the
 * entity lives in the {@code users} service layer, not on this type.
 *
 * @param id the user's id
 * @param email the user's email
 * @param username the user's unique public handle (not PII)
 */
public record User(UUID id, String email, String username) {}
```

In `src/main/java/com/zarlania/api/users/service/UserMapper.java`, update the mapping and javadoc:

```java
  /**
   * Maps an entity to its DTO.
   *
   * @param entity the source entity
   * @return a DTO carrying the entity's id, email, and username
   */
  public User toDto(UserEntity entity) {
    return new User(entity.getId(), entity.getEmail(), entity.getUsername());
  }
```

In `src/main/java/com/zarlania/api/users/repository/UserRepository.java`, add `existsByUsername` after `existsByEmail`:

```java
  /**
   * Reports whether a user with the given username exists.
   *
   * @param username the username to check
   * @return {@code true} if a user with that username exists
   */
  boolean existsByUsername(String username);
```

In `src/main/java/com/zarlania/api/users/service/UserService.java`, do the **minimal** rename only (the uniqueness handling lands in Task 2): rename the parameter, its validation, and `setDisplayName` → `setUsername`. Replace the `create` method body's first half and the entity population:

```java
  /**
   * Creates a user with the given email and username.
   *
   * @param email a non-blank email, unique across users
   * @param username a non-blank unique public handle
   * @return the created user as a DTO
   * @throws IllegalArgumentException if {@code email} or {@code username} is null or blank
   * @throws EmailAlreadyExistsException if a user with that email already exists
   */
  @Transactional
  public User create(String email, String username) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (userRepository.existsByEmail(email)) {
      throw EmailAlreadyExistsException.forEmail(email);
    }
    UserEntity entity = new UserEntity();
    entity.setEmail(email);
    entity.setUsername(username);
    try {
      return userMapper.toDto(userRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException ex) {
      if (isEmailUniquenessViolation(ex)) {
        throw EmailAlreadyExistsException.forEmail(email);
      }
      throw ex;
    }
  }
```

Leave `isEmailUniquenessViolation`, `EMAIL_UNIQUE_CONSTRAINT`, and the read methods unchanged for now (Task 2 refactors them).

- [ ] **Step 4: Run the full build**

Run: `./mvnw -q verify`
Expected: PASS — Flyway applies V1→V3, Hibernate `validate` matches the `username` column, all renamed tests green, coverage ≥ 80%.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__replace_user_display_name_with_username.sql \
        src/main/java/com/zarlania/api/users \
        src/test/java/com/zarlania/api/users \
        src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java
git commit -m "feat: replace user displayName with username handle (schema + rename) (#43)"
```

---

### Task 2: Enforce username uniqueness as a domain exception

Add the username-taken domain failure, mirroring the existing email pattern, and generalize the constraint-name matcher so `create` maps both the email and username unique-constraint violations.

**Files:**
- Create: `src/main/java/com/zarlania/api/users/exception/UsernameAlreadyExistsException.java`
- Modify: `src/main/java/com/zarlania/api/users/service/UserService.java`
- Modify (tests): `UserServiceTest`, `UserServiceUnitTest`

**Interfaces:**
- Consumes: `UserRepository.existsByUsername(String)` (added in Task 1).
- Produces: `UsernameAlreadyExistsException.forUsername(String)` with `getUsername()`; `UserService.create` now throws it on a duplicate handle.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/zarlania/api/users/service/UserServiceTest.java`, add an integration test (import `com.zarlania.api.users.exception.UsernameAlreadyExistsException`):

```java
  @Test
  void createRejectsDuplicateUsername() {
    userService.create("first@example.com", "twin");

    assertThatThrownBy(() -> userService.create("second@example.com", "twin"))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }
```

In `src/test/java/com/zarlania/api/users/service/UserServiceUnitTest.java`, add a unit test for the concurrent-duplicate race on the username constraint (import `UsernameAlreadyExistsException`):

```java
  @Test
  void createTranslatesUsernameUniquenessRaceIntoDomainException() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("race@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("racer")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_USERS_USERNAME\"]"));

    assertThatThrownBy(() -> userService.create("race@example.com", "racer"))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }
```

Also tighten the unrelated-violation assertion in `createRethrowsIntegrityViolationUnrelatedToUniqueness` to prove neither domain exception is raised:

```java
    assertThatThrownBy(() -> userService.create("long@example.com", "waytoolong"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(EmailAlreadyExistsException.class)
        .isNotInstanceOf(UsernameAlreadyExistsException.class);
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./mvnw -q test -Dtest=UserServiceTest,UserServiceUnitTest`
Expected: FAIL — `UsernameAlreadyExistsException` does not exist; the duplicate-username INSERT surfaces a raw `DataIntegrityViolationException`, not the domain exception.

- [ ] **Step 3: Create the exception**

Create `src/main/java/com/zarlania/api/users/exception/UsernameAlreadyExistsException.java`:

```java
package com.zarlania.api.users.exception;

import lombok.Getter;

/** Thrown when creating a user whose username (public handle) already belongs to an account. */
@Getter
public class UsernameAlreadyExistsException extends RuntimeException {

  /** The conflicting username. A public handle (not PII), so it is safe to surface. */
  private final String username;

  private UsernameAlreadyExistsException(String username) {
    super("A user already exists with the username '" + username + "'");
    this.username = username;
  }

  /**
   * Creates the exception for a conflicting username. Unlike the email equivalent, the handle is
   * included in the message because a username is a public handle, not PII.
   *
   * @param username the username already in use
   * @return an exception describing the conflict
   */
  public static UsernameAlreadyExistsException forUsername(String username) {
    return new UsernameAlreadyExistsException(username);
  }
}
```

- [ ] **Step 4: Wire the username check into `UserService`**

In `src/main/java/com/zarlania/api/users/service/UserService.java`:

Add the import `import com.zarlania.api.users.exception.UsernameAlreadyExistsException;` and a second constant:

```java
  /** Name of the email unique constraint in {@code V1__create_users_table.sql}. */
  private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

  /** Name of the username unique constraint in {@code V3__...sql}. */
  private static final String USERNAME_UNIQUE_CONSTRAINT = "uq_users_username";
```

Replace the `create` body's pre-check and catch block to handle both constraints:

```java
    if (userRepository.existsByEmail(email)) {
      throw EmailAlreadyExistsException.forEmail(email);
    }
    if (userRepository.existsByUsername(username)) {
      throw UsernameAlreadyExistsException.forUsername(username);
    }
    UserEntity entity = new UserEntity();
    entity.setEmail(email);
    entity.setUsername(username);
    try {
      // saveAndFlush forces the INSERT now so a concurrent duplicate that slipped past a pre-check
      // surfaces here as the relevant unique-constraint violation. Only those specific violations
      // map to a domain exception; any other integrity failure is rethrown unchanged.
      return userMapper.toDto(userRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException ex) {
      if (isConstraintViolation(ex, EMAIL_UNIQUE_CONSTRAINT)) {
        throw EmailAlreadyExistsException.forEmail(email);
      }
      if (isConstraintViolation(ex, USERNAME_UNIQUE_CONSTRAINT)) {
        throw UsernameAlreadyExistsException.forUsername(username);
      }
      throw ex;
    }
```

Also extend the `create` javadoc with `@throws UsernameAlreadyExistsException if a user with that username already exists`.

Replace the `isEmailUniquenessViolation` helper with a parameterized matcher (mirrors `OrganizationService.isConstraintViolation`):

```java
  /**
   * Reports whether the violation's cause chain names the given constraint. Matching the constraint
   * name (which appears in both H2 and PostgreSQL messages) avoids catching unrelated integrity
   * failures and avoids depending on a JPA-provider-specific typed exception.
   */
  private static boolean isConstraintViolation(
      DataIntegrityViolationException ex, String constraintName) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains(constraintName)) {
        return true;
      }
    }
    return false;
  }
```

- [ ] **Step 5: Run the full build**

Run: `./mvnw -q verify`
Expected: PASS — both new uniqueness tests green, coverage ≥ 80%.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/zarlania/api/users src/test/java/com/zarlania/api/users
git commit -m "feat: reject duplicate username as a domain exception (#43)"
```

---

### Task 3: Release housekeeping (version bump, phase status, PR)

**Files:**
- Modify: `pom.xml` (via `./scripts/bump-version`)
- Modify: `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md` (Phase 3 status row)

- [ ] **Step 1: Bump the version**

This phase changes the `users` domain's public service contract (a `release:minor` in 0.x). Apply the `release:minor` label to the PR and bump:

```bash
./scripts/bump-version bump minor
```

Expected: `pom.xml` `<version>` goes `0.3.0` → `0.4.0`.

- [ ] **Step 2: Update the spec's Phase status table**

The spec instructs updating its status table as each phase lands. In `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md`, change the Phase 3 row:

```markdown
| 3 | `users` handle: replace `displayName` with a unique `username` | ✅ Done (v0.4.0) |
```

and update the top-line Status to note Phase 3 implemented (v0.4.0).

- [ ] **Step 3: Final full build**

Run: `./mvnw -q verify`
Expected: PASS.

- [ ] **Step 4: Commit, push, open the PR**

```bash
git add pom.xml docs/superpowers/specs/2026-06-17-users-and-organizations-design.md
git commit -m "chore: bump version to 0.4.0 for phase 3 release (#43)"
git push -u origin feat/43-phase-3-username
gh pr create --label release:minor \
  --title "feat: replace user displayName with a unique username (#43)" \
  --body "Implements Phase 3 of the users-and-organizations spec. Closes #43."
```

Expected: CI green, including the "Release version bump" check (pom `0.4.0` matches the `release:minor` label vs. the latest `v0.3.0` tag).

---

## Self-Review

**Spec coverage (Phase 3, steps 1–4):**
1. Flyway migration drops `display_name`, adds `username` `NOT NULL` + `uq_users_username` → Task 1 (V3).
2. Replace `displayName` with `username` on entity, DTO, `UserService.create`; surface uniqueness as a domain exception mirroring email → Tasks 1 + 2.
3. Tests: username uniqueness rejected; create/lookup updated; gates green → Tasks 1 + 2.
4. Personal org name (= owner's `username`) globally unique → spec step 4 says the `OrganizationService` pre-check **can** be retired. **Deliberately not done** (user decision): the `organizations` domain keeps enforcing one-personal-org-per-user via the owner-id + org-type pre-check (`existsByUserIdAndRoleAndOrganizationType`), as defense-in-depth that does not depend on the caller naming the personal org after the username. So Phase 3 leaves the `organizations` domain's ownership logic, its `PersonalOrganizationAlreadyExistsException`, and the `idx_memberships_user_role` index untouched. The new unique `username` is complementary (it also makes the personal-org name DB-unique), not a replacement for the pre-check.
- Release `release:minor`/version bump and Phase-status update → Task 3.

**Placeholder scan:** No "TBD"/"add validation"/"similar to" placeholders; every code step shows full code. `43` is an intentionally-deferred external GitHub issue id created in Task 0, not a code placeholder. (Username format rules — case-folding, allowed characters — are deliberately out of scope: the spec asks only for a unique, non-blank handle; YAGNI.)

**Type consistency:** `username` (not `displayName`/`handle`) used throughout; `User(UUID, String email, String username)`; `UserService.create(String email, String username)`; `existsByUsername(String)`; `UsernameAlreadyExistsException.forUsername(String)`/`getUsername()`; constant `USERNAME_UNIQUE_CONSTRAINT = "uq_users_username"` matches the V3 constraint name; `isConstraintViolation(ex, name)` matches the `OrganizationService` helper it mirrors.

> The spec's Phase 3 "step 3" (tests) is folded into Tasks 1–2 (tests are written first in each, per TDD), and spec step 4 (retire the org pre-check) is intentionally skipped (see coverage note 4), so neither has a standalone task. Execute in order: 0, 1, 2, 3.
