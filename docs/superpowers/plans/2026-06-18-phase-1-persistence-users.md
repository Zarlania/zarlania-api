# Phase 1 — Persistence Foundation + `users` Domain — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the JPA + H2 + Flyway persistence foundation and the `users` domain (entity, repository, DTO, service) with audit timestamps and enforced email uniqueness.

**Architecture:** Spring Data JPA over an H2 in-memory database whose schema is owned by Flyway migrations (Hibernate `ddl-auto=validate`). A shared `Auditable` mapped-superclass supplies `createdAt`/`updatedAt` via Spring Data JPA auditing. The `users` domain exposes only DTOs across its boundary; entities stay internal. No HTTP endpoints this phase.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Maven (`./mvnw`), Spring Data JPA, H2, Flyway, Lombok, JUnit 5, AssertJ, Mockito.

This plan implements **Phase 1 only** of `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md`. Phases 2 (`organizations`) and 3 (reference-docs system) are planned later, separately.

## Global Constraints

Every task implicitly includes these (exact values from the spec):

- **Java 25 / Spring Boot 4.1.0 / Maven wrapper `./mvnw`.** New persistence deps inherit versions from the Spring Boot parent — do **not** pin `<version>` for `spring-boot-starter-data-jpa`, `h2`, `flyway-core`, or `lombok`.
- **Flyway owns the schema** (`src/main/resources/db/migration`); Hibernate runs **`ddl-auto=validate`**, never generates DDL.
- **Entities use Lombok** `@Getter`/`@Setter`/`@NoArgsConstructor` only. **NEVER `@Data` or `@EqualsAndHashCode` on a JPA entity.**
- **DTOs are Java `records`** — no Lombok.
- **No cross-domain entity associations** (not exercised in Phase 1; `users` stands alone).
- **Audit timestamps** are `Instant`, stored at microsecond precision, **no truncation**.
- **Tests:** AssertJ for all assertions; Mockito for mocking when a genuine collaborator needs faking; integration tests hit **H2 with the real Flyway-migrated schema**; unit tests touch no database. **Assert observable behavior through the public surface, not mock interactions.**
- **Coverage:** the build enforces **≥ 80% line and branch** (JaCoCo). Lombok-generated methods are excluded via `lombok.config`.
- **Javadoc:** every **public** type and public method needs Javadoc (Checkstyle `google_checks`, `warning` severity fails the build). Keep test classes/methods **package-private** so they need none.
- **Formatting:** run `./mvnw -q spotless:apply` before every commit (google-java-format).
- **Workflow:** work on branch `feat/<issue#>-phase-1-persistence-users`; PR title references `#<issue>`; apply the **`release:minor`** label; bump `pom.xml` `0.1.3 → 0.2.0` with `./scripts/bump-version bump minor` inside the PR.
- **Every commit message ends with the trailer:** `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## File Structure

- `pom.xml` — add the four dependencies (modify).
- `lombok.config` — repo root; mark Lombok output `@Generated` so JaCoCo ignores it (create).
- `src/main/resources/application.properties` — datasource, JPA, Flyway settings (modify).
- `src/main/resources/db/migration/V1__create_users_table.sql` — `users` table (create).
- `com.zarlania.api.persistence.Auditable` — `@MappedSuperclass` audit base (create).
- `com.zarlania.api.persistence.JpaConfig` — `@Configuration @EnableJpaAuditing` (create).
- `com.zarlania.api.users.User` — JPA entity (create).
- `com.zarlania.api.users.UserRepository` — Spring Data repository (create).
- `com.zarlania.api.users.UserDto` — boundary record + `from(User)` (create).
- `com.zarlania.api.users.EmailAlreadyExistsException` — domain exception (create).
- `com.zarlania.api.users.UserService` — service (create).
- Tests mirror these under `src/test/java/...`.
- `docs/adrs/00NN-*.md` — three ADRs (created via `./scripts/adr`).

---

## Setup (before Task 1)

Do this once, at execution start, in an isolated workspace (use the superpowers:using-git-worktrees skill).

- [ ] **Create the Phase 1 issue and capture its number:**

```bash
gh issue create \
  --title "Phase 1: Persistence foundation + users domain" \
  --label enhancement --label "release:minor" \
  --body "Implements Phase 1 of the users-and-organizations spec: Spring Data JPA + H2 + Flyway persistence foundation and the users domain (entity, repository, DTO, service) with audit timestamps and email uniqueness. Spec: docs/superpowers/specs/2026-06-17-users-and-organizations-design.md"
```

Note the printed issue number; call it `<issue#>` below.

- [ ] **Create the branch:**

```bash
git checkout -b feat/<issue#>-phase-1-persistence-users
```

---

## Task 1: Persistence foundation (JPA + H2 + Flyway)

Wire the persistence stack and prove the context boots with Flyway applying the schema. Setup-heavy, so the deliverable is verified by a boot test rather than written strictly test-first.

**Files:**
- Modify: `pom.xml`
- Create: `lombok.config`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/db/migration/V1__create_users_table.sql`
- Test: `src/test/java/com/zarlania/api/persistence/FlywaySchemaTest.java`

**Interfaces:**
- Produces: the `users` table (`id UUID` PK, `email` unique, `created_at`/`updated_at` timestamptz) that Task 2's `User` entity maps to; an H2 datasource + Flyway wiring all later tests rely on.

- [ ] **Step 1: Add the four dependencies to `pom.xml`.**

Insert these inside `<dependencies>`, after the `springdoc` dependency (before the `spring-boot-starter-test` block). No `<version>` — the parent manages them.

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>

		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-core</artifactId>
		</dependency>

		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>runtime</scope>
		</dependency>

		<dependency>
			<groupId>org.projectlombok</groupId>
			<artifactId>lombok</artifactId>
			<optional>true</optional>
		</dependency>
```

- [ ] **Step 2: Create `lombok.config` at the repo root.**

```properties
# Stop Lombok config lookup from walking above the repo root.
config.stopBubbling = true
# Annotate generated methods with @lombok.Generated so JaCoCo excludes them from coverage.
lombok.addLombokGeneratedAnnotation = true
```

- [ ] **Step 3: Append persistence settings to `src/main/resources/application.properties`.**

```properties

# Datasource: H2 in-memory (first pass; swap URL + driver for Postgres later).
# PostgreSQL compatibility mode keeps SQL/behaviour close to the eventual target.
spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver

# Flyway owns the schema; Hibernate only validates the mapped entities against it.
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- [ ] **Step 4: Create the first migration `src/main/resources/db/migration/V1__create_users_table.sql`.**

```sql
CREATE TABLE users (
    id         UUID                        NOT NULL,
    email      VARCHAR(320)                NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_users       PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);
```

- [ ] **Step 5: Write the boot/schema test `src/test/java/com/zarlania/api/persistence/FlywaySchemaTest.java`.**

```java
package com.zarlania.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FlywaySchemaTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flywayCreatesEmptyUsersTable() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    assertThat(count).isZero();
  }
}
```

- [ ] **Step 6: Format, then run the test.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=FlywaySchemaTest`
Expected: PASS. (The Spring context boots, Flyway applies `V1`, the `users` table exists and is empty.) If the context fails to start, read the stack trace — a missing/!mismatched datasource property is the usual cause.

- [ ] **Step 7: Commit.**

```bash
git add pom.xml lombok.config src/main/resources/application.properties \
  src/main/resources/db/migration/V1__create_users_table.sql \
  src/test/java/com/zarlania/api/persistence/FlywaySchemaTest.java
git commit -m "feat: add JPA + H2 + Flyway persistence foundation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Auditing infrastructure + `User` entity + repository

Add the audit base class and auditing config, the `User` entity mapped to the `users` table, and its repository. Prove persistence assigns a UUID and audit timestamps, that lookups work, and that the unique-email constraint bites.

**Files:**
- Create: `src/main/java/com/zarlania/api/persistence/Auditable.java`
- Create: `src/main/java/com/zarlania/api/persistence/JpaConfig.java`
- Create: `src/main/java/com/zarlania/api/users/User.java`
- Create: `src/main/java/com/zarlania/api/users/UserRepository.java`
- Test: `src/test/java/com/zarlania/api/users/UserRepositoryTest.java`

**Interfaces:**
- Consumes: the `users` table from Task 1.
- Produces:
  - `Auditable` — abstract `@MappedSuperclass` with `Instant getCreatedAt()` / `Instant getUpdatedAt()`.
  - `User extends Auditable` — `UUID getId()`, `String getEmail()`, `void setEmail(String)`, no-arg constructor.
  - `UserRepository extends JpaRepository<User, UUID>` — `Optional<User> findByEmail(String)`, `boolean existsByEmail(String)`.

- [ ] **Step 1: Write the failing repository test `src/test/java/com/zarlania/api/users/UserRepositoryTest.java`.**

```java
package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class UserRepositoryTest {

  @Autowired private UserRepository users;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savingAssignsIdAndAuditTimestamps() {
    User user = new User();
    user.setEmail("ada@example.com");

    User saved = users.save(user);
    entityManager.flush();

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByEmailReturnsTheSavedUser() {
    User user = new User();
    user.setEmail("grace@example.com");
    users.save(user);
    entityManager.flush();

    Optional<User> found = users.findByEmail("grace@example.com");

    assertThat(found).isPresent();
    assertThat(found.get().getEmail()).isEqualTo("grace@example.com");
  }

  @Test
  void existsByEmailReflectsPersistedState() {
    assertThat(users.existsByEmail("none@example.com")).isFalse();
    User user = new User();
    user.setEmail("none@example.com");
    users.save(user);
    entityManager.flush();

    assertThat(users.existsByEmail("none@example.com")).isTrue();
  }

  @Test
  void duplicateEmailViolatesTheUniqueConstraint() {
    User first = new User();
    first.setEmail("dup@example.com");
    users.save(first);
    entityManager.flush();

    User second = new User();
    second.setEmail("dup@example.com");

    assertThatThrownBy(
            () -> {
              users.save(second);
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails.**

Run: `./mvnw -q test -Dtest=UserRepositoryTest`
Expected: FAIL — compilation error (`User`, `UserRepository`, `JpaConfig` do not exist yet).

- [ ] **Step 3: Create `Auditable.java`.**

```java
package com.zarlania.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base class for persistent entities, supplying {@code createdAt} / {@code updatedAt} audit
 * timestamps populated by Spring Data JPA auditing. Stored at microsecond precision (no
 * truncation); never set these fields by hand.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class Auditable {

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

- [ ] **Step 4: Create `JpaConfig.java`.**

```java
package com.zarlania.api.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables Spring Data JPA auditing so {@link Auditable} timestamps are populated. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {}
```

- [ ] **Step 5: Create `User.java`.**

```java
package com.zarlania.api.users;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user account, identified by a unique email association. Holds no secrets — credentials live
 * in the future identity domain. Internal to the {@code users} domain; cross boundaries via
 * {@link UserDto}.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends Auditable {

  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;

  @Column(name = "email", nullable = false, length = 320)
  private String email;
}
```

- [ ] **Step 6: Create `UserRepository.java`.**

```java
package com.zarlania.api.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link User}. Internal to the {@code users} domain. */
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Finds a user by exact email.
   *
   * @param email the email to match
   * @return the user, if one exists
   */
  Optional<User> findByEmail(String email);

  /**
   * Reports whether a user with the given email exists.
   *
   * @param email the email to check
   * @return {@code true} if a user with that email exists
   */
  boolean existsByEmail(String email);
}
```

- [ ] **Step 7: Format, then run the test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=UserRepositoryTest`
Expected: PASS (all four tests).

> If Hibernate `validate` rejects `created_at`/`updated_at` with a type mismatch, the migration column type and Hibernate's `Instant` mapping disagree. The fix is to keep the columns as `TIMESTAMP(6) WITH TIME ZONE` (matches Hibernate's default `Instant` → `TIMESTAMP_UTC` mapping, as written in Task 1). Do **not** drop to `ddl-auto=none`.

- [ ] **Step 8: Commit.**

```bash
git add src/main/java/com/zarlania/api/persistence/Auditable.java \
  src/main/java/com/zarlania/api/persistence/JpaConfig.java \
  src/main/java/com/zarlania/api/users/User.java \
  src/main/java/com/zarlania/api/users/UserRepository.java \
  src/test/java/com/zarlania/api/users/UserRepositoryTest.java
git commit -m "feat: add auditing base, User entity and repository

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `UserDto`, domain exception, and `UserService`

Add the boundary DTO, a meaningful domain exception, and the service that creates and looks up users — the public surface of the `users` domain.

**Files:**
- Create: `src/main/java/com/zarlania/api/users/UserDto.java`
- Create: `src/main/java/com/zarlania/api/users/EmailAlreadyExistsException.java`
- Create: `src/main/java/com/zarlania/api/users/UserService.java`
- Test: `src/test/java/com/zarlania/api/users/UserDtoTest.java` (pure unit)
- Test: `src/test/java/com/zarlania/api/users/UserServiceTest.java` (integration)

**Interfaces:**
- Consumes: `User`, `UserRepository` from Task 2.
- Produces:
  - `UserDto(UUID id, String email)` with static `UserDto from(User user)`.
  - `EmailAlreadyExistsException extends RuntimeException`.
  - `UserService` — `UserDto create(String email)`, `Optional<UserDto> findById(UUID id)`, `Optional<UserDto> findByEmail(String email)`.

- [ ] **Step 1: Write the failing pure-unit test `src/test/java/com/zarlania/api/users/UserDtoTest.java`.**

```java
package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserDtoTest {

  @Test
  void fromCopiesIdAndEmail() {
    User user = new User();
    user.setEmail("linus@example.com");

    UserDto dto = UserDto.from(user);

    assertThat(dto.email()).isEqualTo("linus@example.com");
    assertThat(dto.id()).isEqualTo(user.getId());
  }
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `./mvnw -q test -Dtest=UserDtoTest`
Expected: FAIL — compilation error (`UserDto` does not exist).

- [ ] **Step 3: Create `UserDto.java`.**

```java
package com.zarlania.api.users;

import java.util.UUID;

/**
 * Immutable view of a {@link User} for use across the domain boundary and in API responses.
 *
 * @param id the user's id
 * @param email the user's email
 */
public record UserDto(UUID id, String email) {

  /**
   * Maps an entity to its DTO.
   *
   * @param user the source entity
   * @return a DTO carrying the entity's id and email
   */
  public static UserDto from(User user) {
    return new UserDto(user.getId(), user.getEmail());
  }
}
```

- [ ] **Step 4: Run the unit test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=UserDtoTest`
Expected: PASS.

- [ ] **Step 5: Write the failing service test `src/test/java/com/zarlania/api/users/UserServiceTest.java`.**

```java
package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserService.class})
class UserServiceTest {

  @Autowired private UserService userService;

  @Test
  void createPersistsAndReturnsDtoWithId() {
    UserDto created = userService.create("alan@example.com");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("alan@example.com");
    assertThat(userService.findById(created.id())).contains(created);
  }

  @Test
  void createRejectsBlankEmail() {
    assertThatThrownBy(() -> userService.create("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsDuplicateEmail() {
    userService.create("twin@example.com");

    assertThatThrownBy(() -> userService.create("twin@example.com"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void findByEmailReturnsCreatedUser() {
    UserDto created = userService.create("margaret@example.com");

    assertThat(userService.findByEmail("margaret@example.com")).contains(created);
  }

  @Test
  void findByIdIsEmptyForUnknownUser() {
    assertThat(userService.findById(UUID.randomUUID())).isEmpty();
  }
}
```

- [ ] **Step 6: Run it to verify it fails.**

Run: `./mvnw -q test -Dtest=UserServiceTest`
Expected: FAIL — compilation error (`UserService`, `EmailAlreadyExistsException` do not exist).

- [ ] **Step 7: Create `EmailAlreadyExistsException.java`.**

```java
package com.zarlania.api.users;

/** Thrown when creating a user whose email already belongs to an existing account. */
public class EmailAlreadyExistsException extends RuntimeException {

  /**
   * Creates the exception for a conflicting email.
   *
   * @param email the email already in use
   */
  public EmailAlreadyExistsException(String email) {
    super("A user already exists with email: " + email);
  }
}
```

- [ ] **Step 8: Create `UserService.java`.**

```java
package com.zarlania.api.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and looks up users. The public surface of the {@code users} domain. */
@Service
public class UserService {

  private final UserRepository users;

  UserService(UserRepository users) {
    this.users = users;
  }

  /**
   * Creates a user with the given email.
   *
   * @param email a non-blank email, unique across users
   * @return the created user as a DTO
   * @throws IllegalArgumentException if {@code email} is null or blank
   * @throws EmailAlreadyExistsException if a user with that email already exists
   */
  @Transactional
  public UserDto create(String email) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (users.existsByEmail(email)) {
      throw new EmailAlreadyExistsException(email);
    }
    User user = new User();
    user.setEmail(email);
    return UserDto.from(users.save(user));
  }

  /**
   * Finds a user by id.
   *
   * @param id the user id
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<UserDto> findById(UUID id) {
    return users.findById(id).map(UserDto::from);
  }

  /**
   * Finds a user by exact email.
   *
   * @param email the email to match
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<UserDto> findByEmail(String email) {
    return users.findByEmail(email).map(UserDto::from);
  }
}
```

- [ ] **Step 9: Format, then run both new tests to verify they pass.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=UserDtoTest,UserServiceTest`
Expected: PASS (all tests).

- [ ] **Step 10: Commit.**

```bash
git add src/main/java/com/zarlania/api/users/UserDto.java \
  src/main/java/com/zarlania/api/users/EmailAlreadyExistsException.java \
  src/main/java/com/zarlania/api/users/UserService.java \
  src/test/java/com/zarlania/api/users/UserDtoTest.java \
  src/test/java/com/zarlania/api/users/UserServiceTest.java
git commit -m "feat: add UserDto, UserService and email-uniqueness enforcement

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Record the three ADRs

Record the persistence, domain-boundary, and Lombok decisions. Use the **adr-create** skill for the prose (it writes a proper MADR body); the commands below scaffold, and `./scripts/adr check` validates. Tags must already exist in the registry — add the two new ones first.

**Files:**
- Create: `docs/adrs/00NN-adopt-spring-data-jpa-with-h2-and-flyway.md`
- Create: `docs/adrs/00NN-keep-domains-decoupled-in-code-with-db-level-integrity.md`
- Create: `docs/adrs/00NN-adopt-lombok-for-entity-boilerplate.md`
- Modify: `docs/adrs/_tags.md`, `docs/adrs/README.md` (the CLI updates these)

- [ ] **Step 1: Add the two new tags (reuse existing ones otherwise).**

Existing tags: `build, configuration, deployment, documentation, governance, process, quality, release, security`. Add:

```bash
./scripts/adr add-tag persistence --description "Persistence model, datasources, schema, and migrations"
./scripts/adr add-tag architecture --description "Architectural structure, boundaries, and cross-domain conventions"
```

- [ ] **Step 2: Scaffold the persistence ADR and write it with the adr-create skill.**

```bash
./scripts/adr new --name "Adopt Spring Data JPA with H2 and Flyway" --tags "build,persistence"
```

Then fill the body (via the adr-create skill) to state the decision from the spec's ADR-1: Spring Data JPA + H2 in-memory now, **Flyway-owned schema** with Hibernate `ddl-auto=validate`, Postgres later via a datasource/driver swap with migrations unchanged. `./scripts/adr accept <id>`.

- [ ] **Step 3: Scaffold and write the domain-boundary ADR.**

```bash
./scripts/adr new --name "Keep domains decoupled in code with DB-level integrity" --tags "architecture,persistence"
```

Body states spec ADR-2: entities internal; DTOs across boundaries; **no cross-domain entity associations** (never import another domain's entity / no cross-domain `@ManyToOne`); a real **database foreign key** still enforces referential integrity at rest, declared in a migration; cross-domain interaction is an in-process call, never internal HTTP. `./scripts/adr accept <id>`.

- [ ] **Step 4: Scaffold and write the Lombok ADR.**

```bash
./scripts/adr new --name "Adopt Lombok for entity boilerplate" --tags "build,quality"
```

Body states spec ADR-3: Lombok on **entities only** (`@Getter`/`@Setter`/`@NoArgsConstructor`); **`@Data`/`@EqualsAndHashCode` forbidden on JPA entities**; DTOs are records; `lombok.config` sets `addLombokGeneratedAnnotation` so JaCoCo ignores generated methods. `./scripts/adr accept <id>`.

- [ ] **Step 5: Validate and commit.**

Run: `./scripts/adr check`
Expected: `ADR check passed`.

```bash
git add docs/adrs/
git commit -m "docs: record ADRs for persistence, domain boundaries and Lombok

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Finalize (version bump, full verify, PR)

- [ ] **Step 1: Run the full build with every gate.**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS — Spotless, Checkstyle, SpotBugs/FindSecBugs, all tests, and JaCoCo ≥ 80% line/branch all pass. Fix any reported issue at the root cause (never suppress a gate); re-run until green.

- [ ] **Step 2: Bump the version for the minor release.**

```bash
./scripts/bump-version bump minor
```

Confirm `pom.xml` `<version>` is now `0.2.0`.

- [ ] **Step 3: Commit the bump and push.**

```bash
git add pom.xml
git commit -m "chore: bump version to 0.2.0 for phase 1 release

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push -u origin feat/<issue#>-phase-1-persistence-users
```

- [ ] **Step 4: Open the PR (title references the issue; `release:minor` label).**

```bash
gh pr create --label "release:minor" \
  --title "Phase 1: Persistence foundation + users domain (#<issue#>)" \
  --body "$(cat <<'EOF'
Implements Phase 1 of the users-and-organizations spec.

- Spring Data JPA + H2 (in-memory) + Flyway-owned schema (Hibernate ddl-auto=validate)
- Auditing base (createdAt/updatedAt, microsecond precision) via Spring Data JPA auditing
- users domain: User entity, UserRepository, UserDto, UserService with email uniqueness
- ADRs: persistence (JPA/H2/Flyway), domain-boundary convention, Lombok
- Version bumped 0.1.3 -> 0.2.0 (release:minor)

Closes #<issue#>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Update the spec's Phase status table** — mark Phase 1 done in `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md` (commit on the same branch so it rides the PR).

---

## Self-Review (completed during planning)

- **Spec coverage:** deps + `lombok.config` (Task 1) ✓; three ADRs (Task 4) ✓; H2 datasource + `ddl-auto=validate` + Flyway (Task 1) ✓; auditing infra `Auditable`/`@EnableJpaAuditing`/microsecond columns (Task 2) ✓; V1 `users` migration (Task 1) ✓; `User`/`UserRepository` (Task 2), `UserDto`/`UserService` (Task 3) ✓; integration tests on real migrated schema + a pure unit test (Tasks 2–3) ✓; "done when" criteria met by Finalize ✓.
- **Type consistency:** `UserDto.from(User)`, `UserService.create/findById/findByEmail`, `UserRepository.findByEmail/existsByEmail`, and `Auditable.getCreatedAt/getUpdatedAt` names are used identically across tasks.
- **Mockito note:** Phase 1 has no genuine pure-logic collaborator worth faking, and CLAUDE.md forbids mock-interaction tests — so Mockito is intentionally unused here; it lands in Phase 2 where service logic has collaborators. AssertJ is used throughout.
- **No placeholders:** every code/command step is complete (the only `<issue#>` / ADR-id tokens are values produced during execution).
