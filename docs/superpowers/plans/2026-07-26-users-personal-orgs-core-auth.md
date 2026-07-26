# Users, Personal Orgs & Core Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-25-users-personal-orgs-core-auth-design.md`
**Depends on:** spec 1's implementation (Postgres + Flyway + Testcontainers smoke test) merged to master.

**Goal:** Registration with blocking email verification, login, RS256 JWTs with a JWKS endpoint, and rotating refresh-token families with reuse detection — across the `users`, `organizations`, `credentials`, `auth`, and `common.email` packages.

**Architecture:** Spring Security runs as a resource server validating our own JWTs; our controllers own `/auth/**` and mint via Nimbus. Passwords are Argon2id. Anything bearer-shaped that we persist is stored as a SHA-256 hash. One `Clock` bean drives all time. Registration is orchestrated in the `auth` domain in one transaction; the verification email sends after commit via an application event.

**Tech Stack:** Spring Boot 4.1, Spring Security (oauth2-resource-server), Nimbus JOSE (transitive), Argon2 via `spring-security-crypto` + BouncyCastle, Jakarta Validation, Testcontainers.

## Global Constraints

- Everything in plan 1's Global Constraints applies (wrapper, spotless, gates, Docker, commit format `#<ISSUE> <type>: …`).
- Checkstyle: methods < 40 lines, complexity < 10, nesting < 2, no magic numbers → extract constants; constructor injection only (`@RequiredArgsConstructor` allowed; `@Getter`/`@Data`/`@Value`/`@Setter` are compile errors — write getters by hand).
- Every table: `id uuid pk`, `created_at`/`updated_at` `timestamptz(6) not null`; `citext` for case-insensitive uniques; FK constraints always.
- Error contract: RFC 9457 `ProblemDetail` + stable `code` property. Codes used in this plan: `auth.username-taken`, `auth.email-unverified`, `auth.invalid-credentials`, `auth.invalid-token`, `auth.throttled`, `validation.failed`.
- Token TTLs (config, env-overridable): access `PT15M`, refresh family `P30D`, verification `PT24H`, unverified purge `P7D`.
- Stored-token rule: refresh + verification tokens persist only as SHA-256 hex.
- All new endpoints map from the root (no `/api` prefix).
- Time only ever comes from the injected `java.time.Clock` bean.

---

### Task 0: Tracking issue and branch

- [ ] **Step 1: Create the issue (feature template shape)**

```bash
gh issue create \
  --title "feat: account registration, email verification, login, and token refresh" \
  --label feature \
  --body "$(cat <<'EOF'
### Problem

There is no way to create an account or authenticate; the API has no users, organizations, or session model.

### Proposed solution

Implement docs/superpowers/specs/2026-07-25-users-personal-orgs-core-auth-design.md: users/organizations/credentials/auth domains, Argon2id passwords, blocking email verification via a provider behind an EmailSender interface, RS256 JWTs with a JWKS endpoint, and rotating refresh-token families with reuse detection.

### Alternatives considered

Spring Authorization Server and hand-rolled security filters — rejected in the spec's decisions log.

### Is this a breaking change?

No — backwards compatible

### Additional context

Spec 2 of 7; builds on the persistence foundation (spec 1).

### Before submitting

- [x] I searched existing issues and discussions and this is not a duplicate.
- [x] I agree to follow this project's Code of Conduct.
EOF
)"
```

Record the issue number as `<ISSUE>`.

- [ ] **Step 2: Branch**

```bash
git fetch origin master && git checkout -b <ISSUE>-core-auth origin/master
```

---

### Task 1: Dependencies

**Files:** Modify: `pom.xml`

**Interfaces:** Produces the classpath for everything below: resource server (+ Nimbus), validation, Argon2's BouncyCastle backend, `spring-security-test`.

- [ ] **Step 1: Add dependencies**

Main (after `spring-boot-starter-data-jpa`):

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.bouncycastle</groupId>
      <artifactId>bcprov-jdk18on</artifactId>
      <version>${bouncycastle.version}</version>
      <scope>runtime</scope>
    </dependency>
```

Property: `<bouncycastle.version>1.81</bouncycastle.version>` (not in the Boot BOM; Argon2PasswordEncoder requires it at runtime). Test:

```xml
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Verify + commit**

```bash
./mvnw -q validate compile && ./mvnw spotless:apply
git add pom.xml && git commit -m "#<ISSUE> build: add security, validation, and Argon2 dependencies"
```

Note: the resource-server starter secures every endpoint by default, which would break the existing actuator smoke check only at runtime, not compile time; the filter chain lands in Task 8 before any endpoint work. If `./mvnw verify` is run between Tasks 1 and 8, the smoke test still passes (it does not hit HTTP).

---

### Task 2: Migration V1 — the six tables

**Files:**
- Create: `src/main/resources/db/migration/V1__create_account_tables.sql`
- Modify: `src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java`

**Interfaces:** Produces the schema all entities below map to. Exact table/column names here are load-bearing for every later task.

- [ ] **Step 1: Extend the smoke test (failing)**

Add to `ZarlaniaApiApplicationTest`:

```java
  @Test
  void migrationCreatesTheAccountTables() {
    Integer tables =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name IN"
                + " ('users','organizations','organization_memberships',"
                + " 'password_credentials','email_verification_tokens','refresh_tokens')",
            Integer.class);
    assertThat(tables).isEqualTo(6);
  }
```

Run: `./mvnw test -Dtest=ZarlaniaApiApplicationTest` → the new test FAILS (count 0).

- [ ] **Step 2: Write the migration**

`V1__create_account_tables.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                uuid PRIMARY KEY,
    email             citext NOT NULL UNIQUE,
    username          citext NOT NULL UNIQUE,
    email_verified_at timestamptz(6),
    created_at        timestamptz(6) NOT NULL,
    updated_at        timestamptz(6) NOT NULL
);

CREATE TABLE organizations (
    id         uuid PRIMARY KEY,
    name       citext NOT NULL UNIQUE,
    type       text NOT NULL CHECK (type IN ('PERSONAL', 'GENERAL')),
    created_at timestamptz(6) NOT NULL,
    updated_at timestamptz(6) NOT NULL
);

CREATE TABLE organization_memberships (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    user_id         uuid NOT NULL REFERENCES users (id),
    is_owner        boolean NOT NULL,
    created_at      timestamptz(6) NOT NULL,
    updated_at      timestamptz(6) NOT NULL,
    UNIQUE (organization_id, user_id)
);
CREATE INDEX idx_memberships_user ON organization_memberships (user_id);

CREATE TABLE password_credentials (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL UNIQUE REFERENCES users (id),
    password_hash text NOT NULL,
    created_at    timestamptz(6) NOT NULL,
    updated_at    timestamptz(6) NOT NULL
);

CREATE TABLE email_verification_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id),
    token_hash  text NOT NULL UNIQUE,
    expires_at  timestamptz(6) NOT NULL,
    consumed_at timestamptz(6),
    created_at  timestamptz(6) NOT NULL,
    updated_at  timestamptz(6) NOT NULL
);
CREATE INDEX idx_verification_tokens_user ON email_verification_tokens (user_id);

CREATE TABLE refresh_tokens (
    id                uuid PRIMARY KEY,
    family_id         uuid NOT NULL,
    user_id           uuid NOT NULL REFERENCES users (id),
    organization_id   uuid NOT NULL REFERENCES organizations (id),
    token_hash        text NOT NULL UNIQUE,
    family_expires_at timestamptz(6) NOT NULL,
    used_at           timestamptz(6),
    revoked_at        timestamptz(6),
    created_at        timestamptz(6) NOT NULL,
    updated_at        timestamptz(6) NOT NULL
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
```

- [ ] **Step 3: Run, verify pass, commit**

```bash
./mvnw test -Dtest=ZarlaniaApiApplicationTest      # all tests PASS
./mvnw spotless:apply
git add src/main/resources/db/migration/V1__create_account_tables.sql \
        src/test/java/com/zarlania/api/ZarlaniaApiApplicationTest.java
git commit -m "#<ISSUE> feat: create the account, credential, and token tables"
```

---

### Task 3: `BaseEntity`, the `users` domain, and the convention test

**Files:**
- Create: `src/main/java/com/zarlania/api/common/persistence/BaseEntity.java`
- Create: `src/main/java/com/zarlania/api/users/entities/User.java`
- Create: `src/main/java/com/zarlania/api/users/repositories/UserRepository.java`
- Create: `src/main/java/com/zarlania/api/users/dtos/UserDto.java`
- Create: `src/main/java/com/zarlania/api/users/services/UserService.java`
- Create: `src/main/java/com/zarlania/api/common/persistence/ClockConfig.java`
- Test: `src/test/java/com/zarlania/api/common/persistence/BaseEntityConventionTest.java`
- Test: `src/test/java/com/zarlania/api/users/services/UserServiceTest.java`

**Interfaces:**
- Produces: `BaseEntity` (protected no-arg ctor; `UUID getId()`, `Instant getCreatedAt()`, `Instant getUpdatedAt()`); `UserDto(UUID id, String email, String username, boolean emailVerified)`; `UserService` with `UserDto createUnverified(String email, String username)`, `Optional<UserDto> findByIdentifier(String emailOrUsername)`, `Optional<UserDto> findById(UUID id)`, `void markEmailVerified(UUID userId)`, `boolean usernameExists(String username)`, `boolean emailExists(String email)`; a `Clock` bean (`Clock.systemUTC()`).

- [ ] **Step 1: Write the code**

`BaseEntity.java`:

```java
package com.zarlania.api.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** Base for every entity: app-generated UUID v4 id and Hibernate-managed audit timestamps. */
@MappedSuperclass
public abstract class BaseEntity {

  @Id @UuidGenerator private UUID id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public UUID getId() {
    return id;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
```

`ClockConfig.java` (package `common.persistence` is wrong for a clock — put it in `com.zarlania.api.common.time.ClockConfig`):

```java
package com.zarlania.api.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
```

`User.java`:

```java
package com.zarlania.api.users.entities;

import com.zarlania.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String email;

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String username;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  protected User() {}

  public User(String email, String username) {
    this.email = email;
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public String getUsername() {
    return username;
  }

  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  public void markEmailVerified(Instant at) {
    this.emailVerifiedAt = at;
  }
}
```

`UserRepository.java`:

```java
package com.zarlania.api.users.repositories;

import com.zarlania.api.users.entities.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);

  Optional<User> findByUsername(String username);

  boolean existsByEmail(String email);

  boolean existsByUsername(String username);

  List<User> findByEmailVerifiedAtIsNullAndCreatedAtBefore(Instant cutoff);
}
```

`UserDto.java`:

```java
package com.zarlania.api.users.dtos;

import java.util.UUID;

public record UserDto(UUID id, String email, String username, boolean emailVerified) {}
```

`UserService.java`:

```java
package com.zarlania.api.users.services;

import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository users;
  private final Clock clock;

  @Transactional
  public UserDto createUnverified(String email, String username) {
    return toDto(users.save(new User(email, username)));
  }

  @Transactional(readOnly = true)
  public Optional<UserDto> findByIdentifier(String emailOrUsername) {
    return users
        .findByEmail(emailOrUsername)
        .or(() -> users.findByUsername(emailOrUsername))
        .map(this::toDto);
  }

  @Transactional(readOnly = true)
  public Optional<UserDto> findById(UUID id) {
    return users.findById(id).map(this::toDto);
  }

  @Transactional(readOnly = true)
  public boolean usernameExists(String username) {
    return users.existsByUsername(username);
  }

  @Transactional(readOnly = true)
  public boolean emailExists(String email) {
    return users.existsByEmail(email);
  }

  @Transactional
  public void markEmailVerified(UUID userId) {
    users.findById(userId).orElseThrow().markEmailVerified(clock.instant());
  }

  private UserDto toDto(User user) {
    return new UserDto(user.getId(), user.getEmail(), user.getUsername(), user.isEmailVerified());
  }
}
```

- [ ] **Step 2: The convention test (integration, Testcontainers)**

`BaseEntityConventionTest.java` — full `@SpringBootTest` reusing the smoke-test container pattern:

```java
package com.zarlania.api.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class BaseEntityConventionTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired private UserRepository users;

  @Test
  void saveAssignsUuidAndMicrosecondTimestamps() {
    User saved = users.saveAndFlush(new User("conv@example.com", "convention"));
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void updateMovesUpdatedAtButNotCreatedAt() {
    User saved = users.saveAndFlush(new User("conv2@example.com", "convention2"));
    var created = saved.getCreatedAt();
    saved.markEmailVerified(created.plus(1, ChronoUnit.SECONDS));
    User updated = users.saveAndFlush(saved);
    assertThat(updated.getCreatedAt()).isEqualTo(created);
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(created);
  }

  @Test
  void emailUniquenessIsCaseInsensitive() {
    users.saveAndFlush(new User("Case@Example.com", "casetest"));
    assertThat(users.existsByEmail("case@example.com")).isTrue();
  }
}
```

`UserServiceTest.java` — plain unit test with Mockito mocks of `UserRepository` and a fixed `Clock` (`Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC)`), covering: `findByIdentifier` falls back from email to username; `markEmailVerified` stamps the fixed clock's instant (verify via captured entity).

- [ ] **Step 3: Fail → pass → commit**

Run the convention test before writing the entity to see it fail to compile/fail; then:

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/common src/main/java/com/zarlania/api/users src/test/java
git commit -m "#<ISSUE> feat: add BaseEntity and the users domain"
```

Note: if `ddl-auto: validate` rejects the `citext` columns' type, keep the `columnDefinition = "citext"` attribute (it makes the validator compare against `citext`) — do not change the column to `varchar`.

---

### Task 4: The `organizations` domain (personal orgs)

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/entities/Organization.java`
- Create: `src/main/java/com/zarlania/api/organizations/entities/OrganizationType.java`
- Create: `src/main/java/com/zarlania/api/organizations/entities/Membership.java`
- Create: `src/main/java/com/zarlania/api/organizations/repositories/OrganizationRepository.java`
- Create: `src/main/java/com/zarlania/api/organizations/repositories/MembershipRepository.java`
- Create: `src/main/java/com/zarlania/api/organizations/dtos/OrganizationDto.java`
- Create: `src/main/java/com/zarlania/api/organizations/services/OrganizationService.java`
- Test: `src/test/java/com/zarlania/api/organizations/services/OrganizationServiceTest.java` (integration, same container pattern as Task 3)

**Interfaces:**
- Produces: `OrganizationDto(UUID id, String name, OrganizationType type)`; `OrganizationType { PERSONAL, GENERAL }`; `OrganizationService` with `OrganizationDto createPersonalOrganization(UUID ownerUserId, String name)` and `Optional<OrganizationDto> personalOrganizationOf(UUID userId)` and `Optional<OrganizationDto> findById(UUID id)` and `boolean isMember(UUID userId, UUID organizationId)`.

- [ ] **Step 1: Entities**

`OrganizationType.java`: `public enum OrganizationType { PERSONAL, GENERAL }`

`Organization.java`:

```java
package com.zarlania.api.organizations.entities;

import com.zarlania.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrganizationType type;

  protected Organization() {}

  public Organization(String name, OrganizationType type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public OrganizationType getType() {
    return type;
  }
}
```

`Membership.java` — `organization` is a mapped in-domain relation; `userId` is a plain FK id (users is a foreign domain):

```java
package com.zarlania.api.organizations.entities;

import com.zarlania.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "organization_memberships")
public class Membership extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id")
  private Organization organization;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "is_owner", nullable = false)
  private boolean owner;

  protected Membership() {}

  public Membership(Organization organization, UUID userId, boolean owner) {
    this.organization = organization;
    this.userId = userId;
    this.owner = owner;
  }

  public Organization getOrganization() {
    return organization;
  }

  public UUID getUserId() {
    return userId;
  }

  public boolean isOwner() {
    return owner;
  }
}
```

Repositories:

```java
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  List<Membership> findByUserId(UUID userId);

  boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);
}
```

(full package/import headers as in the patterns above).

- [ ] **Step 2: Service**

```java
package com.zarlania.api.organizations.services;

import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.entities.Membership;
import com.zarlania.api.organizations.entities.Organization;
import com.zarlania.api.organizations.entities.OrganizationType;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;

  @Transactional
  public OrganizationDto createPersonalOrganization(UUID ownerUserId, String name) {
    Organization org = organizations.save(new Organization(name, OrganizationType.PERSONAL));
    memberships.save(new Membership(org, ownerUserId, true));
    return toDto(org);
  }

  @Transactional(readOnly = true)
  public Optional<OrganizationDto> personalOrganizationOf(UUID userId) {
    return memberships.findByUserId(userId).stream()
        .map(Membership::getOrganization)
        .filter(org -> org.getType() == OrganizationType.PERSONAL)
        .findFirst()
        .map(this::toDto);
  }

  @Transactional(readOnly = true)
  public Optional<OrganizationDto> findById(UUID id) {
    return organizations.findById(id).map(this::toDto);
  }

  @Transactional(readOnly = true)
  public boolean isMember(UUID userId, UUID organizationId) {
    return memberships.existsByUserIdAndOrganizationId(userId, organizationId);
  }

  private OrganizationDto toDto(Organization org) {
    return new OrganizationDto(org.getId(), org.getName(), org.getType());
  }
}
```

- [ ] **Step 3: Integration test → pass → commit**

`OrganizationServiceTest` (container pattern from Task 3): `createPersonalOrganization` persists an org of type `PERSONAL` and an owner membership (`personalOrganizationOf` finds it; `isMember` true for the owner, false for a random UUID). Run fail-first (service absent), implement, then:

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/organizations src/test/java/com/zarlania/api/organizations
git commit -m "#<ISSUE> feat: add the organizations domain with personal orgs"
```

---

### Task 5: The `credentials` domain — Argon2 passwords

**Files:**
- Create: `src/main/java/com/zarlania/api/credentials/entities/PasswordCredential.java`
- Create: `src/main/java/com/zarlania/api/credentials/repositories/PasswordCredentialRepository.java`
- Create: `src/main/java/com/zarlania/api/credentials/services/CredentialsService.java`
- Create: `src/main/java/com/zarlania/api/credentials/PasswordEncoderConfig.java`
- Test: `src/test/java/com/zarlania/api/credentials/services/CredentialsServiceTest.java` (unit; mock repository, real encoder with test-fast params)

**Interfaces:**
- Produces: `CredentialsService` with `void createPassword(UUID userId, String rawPassword)` and `boolean passwordMatches(UUID userId, String rawPassword)`.

- [ ] **Step 1: Entity + repository**

`PasswordCredential` extends `BaseEntity`; fields `@Column(name = "user_id", nullable = false, unique = true) UUID userId` and `@Column(name = "password_hash", nullable = false) String passwordHash`; constructor `(UUID userId, String passwordHash)`; getters; `void replaceHash(String newHash)`. Repository: `Optional<PasswordCredential> findByUserId(UUID userId);`.

- [ ] **Step 2: Encoder config — OWASP baseline, constants named**

```java
package com.zarlania.api.credentials;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19_456; // 19 MiB, OWASP Argon2id baseline
  private static final int ITERATIONS = 2;

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(
        SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  }
}
```

- [ ] **Step 3: Service**

```java
@Service
@RequiredArgsConstructor
public class CredentialsService {

  private final PasswordCredentialRepository credentials;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public void createPassword(UUID userId, String rawPassword) {
    credentials.save(new PasswordCredential(userId, passwordEncoder.encode(rawPassword)));
  }

  @Transactional(readOnly = true)
  public boolean passwordMatches(UUID userId, String rawPassword) {
    return credentials
        .findByUserId(userId)
        .map(c -> passwordEncoder.matches(rawPassword, c.getPasswordHash()))
        .orElse(false);
  }
}
```

- [ ] **Step 4: Unit test → commit**

Tests (mock repository; encoder = `new Argon2PasswordEncoder(16, 32, 1, 1024, 1)` for speed): `passwordMatches` true for the stored hash of "correct horse battery staple!" and false for a wrong password; false for an unknown user id. Fail-first, implement, then:

```bash
./mvnw test -Dtest=CredentialsServiceTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/credentials src/test/java/com/zarlania/api/credentials
git commit -m "#<ISSUE> feat: add Argon2id password credentials"
```

---

### Task 6: `common.email` — the sender port and Resend adapter

**Files:**
- Create: `src/main/java/com/zarlania/api/common/email/EmailMessage.java`
- Create: `src/main/java/com/zarlania/api/common/email/EmailSender.java`
- Create: `src/main/java/com/zarlania/api/common/email/ResendEmailSender.java`
- Create: `src/main/java/com/zarlania/api/common/email/LoggingEmailSender.java`
- Create: `src/main/java/com/zarlania/api/common/email/EmailConfig.java`
- Test: `src/test/java/com/zarlania/api/common/email/ResendEmailSenderTest.java`

**Interfaces:**
- Produces: `EmailMessage(String to, String subject, String textBody)`; `EmailSender { void send(EmailMessage message); }`. Config keys: `zarlania.email.from` (default `no-reply@zarlania.com`), `zarlania.email.resend-api-key` (`${RESEND_API_KEY:}`).

- [ ] **Step 1: Port + adapters**

`EmailMessage` and `EmailSender` as above (records/interface, one file each). `ResendEmailSender` implements `EmailSender` using `RestClient` (constructor takes a built `RestClient` and the from-address): POST `https://api.resend.com/emails` with JSON body `{"from": from, "to": [message.to()], "subject": message.subject(), "text": message.textBody()}` and header `Authorization: Bearer <key>`; non-2xx → throw `IllegalStateException` with the status (the caller decides; registration treats it as a 500). `LoggingEmailSender` implements the port with `@Slf4j`, logging recipient + subject + body at INFO — the local/dev fallback so no provider account is needed to develop.

`EmailConfig`:

```java
package com.zarlania.api.common.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailConfig {

  private static final String RESEND_BASE_URL = "https://api.resend.com";
  private static final String PRODUCTION_PROFILE = "production";

  @Bean
  public EmailSender emailSender(
      @Value("${zarlania.email.resend-api-key:}") String apiKey,
      @Value("${zarlania.email.from}") String from,
      Environment environment) {
    boolean production = environment.matchesProfiles(PRODUCTION_PROFILE);
    if (apiKey.isBlank()) {
      if (production) {
        throw new IllegalStateException("RESEND_API_KEY must be set in production");
      }
      return new LoggingEmailSender();
    }
    RestClient client =
        RestClient.builder()
            .baseUrl(RESEND_BASE_URL)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    return new ResendEmailSender(client, from);
  }
}
```

Add to `application.yml` under the existing `zarlania:` key:

```yaml
  email:
    from: ${EMAIL_FROM:no-reply@zarlania.com}
    resend-api-key: ${RESEND_API_KEY:}
```

- [ ] **Step 2: Test → commit**

`ResendEmailSenderTest`: build the sender against a `RestClient` bound to a MockRestServiceServer (`RestClient.builder(...)` with `MockServerRestClientCustomizer`, or use `RestClient` + `MockRestServiceServer.bindTo`) asserting the POST path, bearer header, and JSON body fields; and that a 500 response throws. Also assert `EmailConfig` returns `LoggingEmailSender` when the key is blank and throws when blank in the production profile (call the bean method directly with a `MockEnvironment`). Fail-first → implement → pass:

```bash
./mvnw test -Dtest=ResendEmailSenderTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/common/email src/test/java/com/zarlania/api/common/email src/main/resources/application.yml
git commit -m "#<ISSUE> feat: add the EmailSender port with Resend and logging adapters"
```

---

### Task 7: Email verification tokens

**Files:**
- Create: `src/main/java/com/zarlania/api/common/security/TokenHasher.java`
- Create: `src/main/java/com/zarlania/api/credentials/entities/EmailVerificationToken.java`
- Create: `src/main/java/com/zarlania/api/credentials/repositories/EmailVerificationTokenRepository.java`
- Create: `src/main/java/com/zarlania/api/credentials/services/EmailVerificationService.java`
- Test: `src/test/java/com/zarlania/api/common/security/TokenHasherTest.java`
- Test: `src/test/java/com/zarlania/api/credentials/services/EmailVerificationServiceTest.java`

**Interfaces:**
- Produces: `TokenHasher.sha256Hex(String raw)` (static, lowercase hex) and `TokenHasher.newUrlSafeToken()` (static, 256-bit from `SecureRandom`, Base64 URL-safe without padding); `EmailVerificationService` with `String issue(UUID userId)` (returns the RAW token; invalidates prior unconsumed tokens; expiry = clock + `zarlania.auth.verification-token-ttl`) and `Optional<UUID> consume(String rawToken)` (valid+unexpired+unconsumed → stamp consumed, return user id; otherwise empty).

- [ ] **Step 1: TokenHasher**

```java
package com.zarlania.api.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Raw bearer secrets are never persisted; only their SHA-256 is stored or looked up. */
public final class TokenHasher {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenHasher() {}

  public static String newUrlSafeToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
```

- [ ] **Step 2: Entity, repository, service**

`EmailVerificationToken` extends `BaseEntity`: `UUID userId`, `String tokenHash` (unique), `Instant expiresAt`, `Instant consumedAt` (nullable); `boolean isUsable(Instant now)` → `consumedAt == null && now.isBefore(expiresAt)`; `void consume(Instant at)`. Repository: `Optional<EmailVerificationToken> findByTokenHash(String hash);` `void deleteByUserIdAndConsumedAtIsNull(UUID userId);` `void deleteByUserId(UUID userId);`.

`EmailVerificationService` (constructor-injected repo, `Clock`, and `@Value("${zarlania.auth.verification-token-ttl}") Duration ttl` via a small `@ConfigurationProperties` — see Task 8's `AuthProperties`, which this service consumes as `authProperties.verificationTokenTtl()`):

```java
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

  private final EmailVerificationTokenRepository tokens;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Transactional
  public String issue(UUID userId) {
    tokens.deleteByUserIdAndConsumedAtIsNull(userId);
    String raw = TokenHasher.newUrlSafeToken();
    Instant expiresAt = clock.instant().plus(authProperties.verificationTokenTtl());
    tokens.save(new EmailVerificationToken(userId, TokenHasher.sha256Hex(raw), expiresAt));
    return raw;
  }

  @Transactional
  public Optional<UUID> consume(String rawToken) {
    return tokens
        .findByTokenHash(TokenHasher.sha256Hex(rawToken))
        .filter(token -> token.isUsable(clock.instant()))
        .map(
            token -> {
              token.consume(clock.instant());
              return token.getUserId();
            });
  }
}
```

(Ordering note: `AuthProperties` is defined in Task 8. To keep this task independently compilable, create the properties record here instead, exactly as specified in Task 8 Step 1, and Task 8 then reuses it.)

- [ ] **Step 3: Tests → commit**

`TokenHasherTest`: `sha256Hex("abc")` equals `"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"`; two `newUrlSafeToken()` calls differ and contain no `+/=`. `EmailVerificationServiceTest` (mock repo, fixed clock): `issue` deletes prior unconsumed and saves a hash ≠ raw with expiry now+24h; `consume` on a usable token stamps and returns the user id; expired (clock advanced 25h) and already-consumed both return empty. Fail-first → implement → pass:

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: add hashed, expiring email verification tokens"
```

---

### Task 8: JWT keys, minting, and the JWKS endpoint

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/AuthProperties.java` (or reuse from Task 7)
- Create: `src/main/java/com/zarlania/api/auth/services/JwtKeys.java`
- Create: `src/main/java/com/zarlania/api/auth/services/JwtService.java`
- Create: `src/main/java/com/zarlania/api/auth/controllers/JwksController.java`
- Test: `src/test/java/com/zarlania/api/auth/services/JwtServiceTest.java`

**Interfaces:**
- Produces:
  - `AuthProperties` — `@ConfigurationProperties(prefix = "zarlania.auth")` record: `(String issuer, Duration accessTokenTtl, Duration refreshFamilyLifetime, Duration verificationTokenTtl, Duration unverifiedAccountMaxAge, boolean cookieSecure, String jwtPrivateKeyPem, String jwtRetiredPublicKeysPem)`. Registered via `@ConfigurationPropertiesScan` on the application class.
  - `JwtKeys` — bean exposing `RSAKey signingKey()` (private+public, `kid` = RFC 7638 thumbprint) and `JWKSet publicJwkSet()` (signing public + retired publics). Built from `jwtPrivateKeyPem` (PKCS#8 PEM); when blank: production profile → startup failure; otherwise generate an ephemeral 2048-bit keypair.
  - `JwtService` — `String mint(UUID userId, UUID organizationId, String kind)` (RS256; claims `iss`, `sub`, `org`, `kind`, `jti` random UUID, `iat` now, `exp` now+accessTokenTtl — from the `Clock`); `JwtDecoder jwtDecoder()` built from the local `publicJwkSet()` (no HTTP self-call).
- Config added to `application.yml` under `zarlania:`:

```yaml
  auth:
    issuer: ${JWT_ISSUER:https://api.zarlania.com}
    access-token-ttl: PT15M
    refresh-family-lifetime: P30D
    verification-token-ttl: PT24H
    unverified-account-max-age: P7D
    cookie-secure: ${AUTH_COOKIE_SECURE:true}
    jwt-private-key-pem: ${JWT_PRIVATE_KEY:}
    jwt-retired-public-keys-pem: ${JWT_RETIRED_PUBLIC_KEYS:}
```

- [ ] **Step 1: Failing test**

`JwtServiceTest` (no Spring context; construct `JwtKeys` with a generated test PEM, fixed clock): minted token parses as signed JWT (Nimbus `SignedJWT.parse`), header alg RS256 and kid = thumbprint; claims `iss`/`sub`/`org`/`kind` round-trip; `exp - iat` = 15 minutes; signature verifies against `publicJwkSet()`. Also: blank PEM + production flag → `IllegalStateException`; blank PEM otherwise → ephemeral key that still signs/verifies. Run → compile failure.

- [ ] **Step 2: Implement**

`JwtKeys` (constructor takes `AuthProperties` and `Environment`): parse PKCS#8 via `PEMDecoder`-free plain JDK — strip `-----BEGIN/END PRIVATE KEY-----` + whitespace, Base64-decode, `KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes))`, derive the public key via `RSAPrivateCrtKey` → `RSAPublicKeySpec`. Build `new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint()...`. Retired keys: the env var holds zero or more concatenated `-----BEGIN PUBLIC KEY-----` blocks; split on the BEGIN marker, parse each with `X509EncodedKeySpec`, add as public-only `RSAKey`s. Ephemeral path: `KeyPairGenerator.getInstance("RSA")` at 2048.

`JwtService.mint` with Nimbus:

```java
public String mint(UUID userId, UUID organizationId, String kind) {
  Instant now = clock.instant();
  JWTClaimsSet claims =
      new JWTClaimsSet.Builder()
          .issuer(authProperties.issuer())
          .subject(userId.toString())
          .claim("org", organizationId.toString())
          .claim("kind", kind)
          .jwtID(UUID.randomUUID().toString())
          .issueTime(Date.from(now))
          .expirationTime(Date.from(now.plus(authProperties.accessTokenTtl())))
          .build();
  try {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwtKeys.signingKey().getKeyID()).build(),
            claims);
    jwt.sign(new RSASSASigner(jwtKeys.signingKey()));
    return jwt.serialize();
  } catch (JOSEException e) {
    throw new IllegalStateException("JWT signing failed", e);
  }
}
```

`JwksController`: `@RestController`, `@GetMapping("/.well-known/jwks.json")` returning `Map<String, Object>` from `jwtKeys.publicJwkSet().toJSONObject()`.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=JwtServiceTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/auth src/main/resources/application.yml src/test/java/com/zarlania/api/auth
git commit -m "#<ISSUE> feat: mint RS256 JWTs with rotatable keys and a JWKS endpoint"
```

---

### Task 9: Security filter chain, principal, CORS, `/users/me`

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/SecurityConfig.java`
- Create: `src/main/java/com/zarlania/api/auth/AuthPrincipal.java`
- Create: `src/main/java/com/zarlania/api/users/controllers/UserController.java`
- Create: `src/main/java/com/zarlania/api/users/dtos/MeResponse.java`
- Test: `src/test/java/com/zarlania/api/auth/SecurityConfigTest.java`

**Interfaces:**
- Produces: `AuthPrincipal(UUID userId, UUID organizationId, String kind)`; controllers retrieve it as the authentication principal. Public paths: `/auth/**`, `/.well-known/jwks.json`, `/actuator/health`. Everything else requires a valid JWT. `MeResponse(UserDto user, OrganizationDto organization)`.

- [ ] **Step 1: Failing test**

`SecurityConfigTest` — `@SpringBootTest` + Testcontainers (Task 3 pattern) + `@AutoConfigureMockMvc`: `GET /users/me` without a token → 401; with a token minted by `JwtService` for a seeded user (insert via `UserService` + `OrganizationService`) → 200 and JSON `user.username` + `organization.type == "PERSONAL"`; `GET /.well-known/jwks.json` → 200 with a `keys` array.

- [ ] **Step 2: Implement**

`SecurityConfig`:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtKeys jwtKeys;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/auth/**", "/.well-known/jwks.json", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(authConverter())));
    return http.build();
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withJwkSource(
            new ImmutableJWKSet<>(jwtKeys.publicJwkSet()))
        .build();
  }

  private Converter<Jwt, AbstractAuthenticationToken> authConverter() {
    return jwt -> {
      AuthPrincipal principal =
          new AuthPrincipal(
              UUID.fromString(jwt.getSubject()),
              UUID.fromString(jwt.getClaimAsString("org")),
              jwt.getClaimAsString("kind"));
      return new UsernamePasswordAuthenticationToken(principal, jwt, List.of());
    };
  }
}
```

(CSRF disabled: bearer API; the one cookie-reading endpoint `/auth/refresh` is defended by `SameSite=Strict` + `Path=/auth` + CORS — spec rationale.) CORS bean: `CorsConfigurationSource` reading `zarlania.cors.allowed-origins` (already in `application.yml`), allowing credentials, methods `GET,POST,PATCH,DELETE,OPTIONS`, headers `Authorization,Content-Type`.

`UserController`:

```java
@RestController
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final OrganizationService organizationService;

  @GetMapping("/users/me")
  public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
    UserDto user = userService.findById(principal.userId()).orElseThrow();
    OrganizationDto org = organizationService.findById(principal.organizationId()).orElseThrow();
    return new MeResponse(user, org);
  }
}
```

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=SecurityConfigTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: enforce JWT resource-server security and add /users/me"
```

---

### Task 10: Refresh-token families

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/entities/RefreshToken.java`
- Create: `src/main/java/com/zarlania/api/auth/repositories/RefreshTokenRepository.java`
- Create: `src/main/java/com/zarlania/api/auth/services/RefreshTokenService.java`
- Create: `src/main/java/com/zarlania/api/auth/services/ReusedRefreshTokenException.java`
- Test: `src/test/java/com/zarlania/api/auth/services/RefreshTokenServiceTest.java` (integration, Task 3 container pattern — the queries matter)

**Interfaces:**
- Produces: `record IssuedRefreshToken(String raw, Instant familyExpiresAt)`; `record RefreshRotation(String newRaw, UUID userId, UUID organizationId, Instant familyExpiresAt)`; `RefreshTokenService` with `IssuedRefreshToken startFamily(UUID userId, UUID organizationId)`, `RefreshRotation rotate(String raw)` (throws `ReusedRefreshTokenException` on reuse — after revoking the family; empty/unknown/expired → throws `InvalidRefreshTokenException` — create it beside the reuse one), `void revokeFamilyOf(String raw)` (logout; unknown token is a no-op).

- [ ] **Step 1: Failing integration test**

Seed one user + personal org (via services). Cases: `startFamily` returns a raw token whose hash row exists with `family_expires_at` = now+30d; `rotate` returns a *different* raw, marks the old row used, new row shares `family_id` and `family_expires_at`; **rotating the first raw again throws `ReusedRefreshTokenException` and every row in the family is revoked** (including the newest); rotating an unknown string throws `InvalidRefreshTokenException`; a family older than 30 days (insert with a past `family_expires_at` directly via the repository) refuses rotation; `revokeFamilyOf` then `rotate` throws.

- [ ] **Step 2: Implement**

`RefreshToken` extends `BaseEntity`: `UUID familyId`, `UUID userId`, `UUID organizationId`, `String tokenHash` (unique), `Instant familyExpiresAt`, `Instant usedAt`, `Instant revokedAt`; helpers `boolean isActive(Instant now)` (`usedAt == null && revokedAt == null && now.isBefore(familyExpiresAt)`), `void markUsed(Instant at)`, `void revoke(Instant at)`. Repository: `Optional<RefreshToken> findByTokenHash(String hash);` `List<RefreshToken> findByFamilyId(UUID familyId);`.

`RefreshTokenService` (repo, `AuthProperties`, `Clock`):

```java
@Transactional
public IssuedRefreshToken startFamily(UUID userId, UUID organizationId) {
  String raw = TokenHasher.newUrlSafeToken();
  Instant familyExpiresAt = clock.instant().plus(authProperties.refreshFamilyLifetime());
  tokens.save(
      new RefreshToken(
          UUID.randomUUID(), userId, organizationId, TokenHasher.sha256Hex(raw), familyExpiresAt));
  return new IssuedRefreshToken(raw, familyExpiresAt);
}

@Transactional
public RefreshRotation rotate(String raw) {
  RefreshToken current =
      tokens
          .findByTokenHash(TokenHasher.sha256Hex(raw))
          .orElseThrow(InvalidRefreshTokenException::new);
  Instant now = clock.instant();
  if (current.getUsedAt() != null) {
    revokeFamily(current.getFamilyId(), now); // reuse = theft signal
    throw new ReusedRefreshTokenException();
  }
  if (!current.isActive(now)) {
    throw new InvalidRefreshTokenException();
  }
  current.markUsed(now);
  String newRaw = TokenHasher.newUrlSafeToken();
  tokens.save(
      new RefreshToken(
          current.getFamilyId(),
          current.getUserId(),
          current.getOrganizationId(),
          TokenHasher.sha256Hex(newRaw),
          current.getFamilyExpiresAt()));
  return new RefreshRotation(
      newRaw, current.getUserId(), current.getOrganizationId(), current.getFamilyExpiresAt());
}
```

`revokeFamilyOf(raw)`: look up by hash, if present revoke all rows of the family (`revoke(now)` on each unrevoked). `revokeFamily(familyId, now)` private helper used by both paths.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=RefreshTokenServiceTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api/auth src/test/java/com/zarlania/api/auth
git commit -m "#<ISSUE> feat: add rotating refresh-token families with reuse revocation"
```

---

### Task 11: Problem details, registration, verify, resend

**Files:**
- Create: `src/main/java/com/zarlania/api/common/errors/ApiException.java`
- Create: `src/main/java/com/zarlania/api/common/errors/ErrorCode.java`
- Create: `src/main/java/com/zarlania/api/common/errors/GlobalExceptionHandler.java`
- Create: `src/main/java/com/zarlania/api/auth/services/RegistrationService.java`
- Create: `src/main/java/com/zarlania/api/auth/controllers/AuthController.java` (register/verify/resend endpoints; extended in Task 12)
- Create: `src/main/java/com/zarlania/api/auth/dtos/RegisterRequest.java`, `VerifyRequest.java`, `ResendRequest.java`
- Test: `src/test/java/com/zarlania/api/auth/controllers/RegistrationFlowTest.java` (integration + MockMvc)
- Test: `src/test/java/com/zarlania/api/testsupport/RecordingEmailSender.java`

**Interfaces:**
- Produces:
  - `ErrorCode` enum: `USERNAME_TAKEN("auth.username-taken", 409)`, `EMAIL_UNVERIFIED("auth.email-unverified", 403)`, `INVALID_CREDENTIALS("auth.invalid-credentials", 401)`, `INVALID_TOKEN("auth.invalid-token", 400)`, `THROTTLED("auth.throttled", 429)`, `VALIDATION_FAILED("validation.failed", 400)` — each `(String code, int status)` with getters.
  - `ApiException extends RuntimeException` carrying an `ErrorCode`.
  - `GlobalExceptionHandler` (`@RestControllerAdvice`): `ApiException` → `ProblemDetail` with `status`, `detail` = exception message, property `"code"` = the code string; `MethodArgumentNotValidException` → 400 with `"code": "validation.failed"` and property `"errors"` = map of field → message. No stack traces or internal messages in any 5xx (generic detail "Unexpected error").
  - `RegistrationService`: `void register(String email, String username, String rawPassword)`, `boolean verify(String rawToken)`, `void resend(String email)`.
  - `RecordingEmailSender` (test support, `@Component` + `@Profile("test-recording")` or registered via `@TestConfiguration` + `@Primary`): implements `EmailSender`, stores messages in a `CopyOnWriteArrayList<EmailMessage>` with `List<EmailMessage> messages()` and `void clear()`.
  - Endpoints: `POST /auth/register` → 202 always (except 409 username / 400 validation); `POST /auth/verify` `{token}` → 200 or 400 `auth.invalid-token`; `POST /auth/resend` `{email}` → 202 always.
- Request records with validation: `RegisterRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "[a-z0-9-]{3,30}") String username, @NotBlank @Size(min = 12, max = 128) String password)`.

- [ ] **Step 1: Failing flow test**

`RegistrationFlowTest` (`@SpringBootTest` + `@AutoConfigureMockMvc` + container pattern; `@Import(RecordingEmailSenderConfig.class)` where the config declares the recording sender as `@Primary`): register happy path → 202, one email whose body contains a token (extract with a regex on the verification URL `https://zarlania.com/verify-email?token=([A-Za-z0-9_-]+)`); verify with that token → 200 and a second register attempt with the same email now yields the "already registered" notice email, not a verification email; taken username → 409 `code=auth.username-taken`; register with 8-char password → 400 `validation.failed`; verify with garbage → 400 `auth.invalid-token`; resend for an unverified email → 202 + new email; resend for a nonexistent email → 202 + **no** email.

- [ ] **Step 2: Implement**

`RegistrationService` (deps: `UserService`, `CredentialsService`, `OrganizationService`, `EmailVerificationService`, `EmailSender`, `ApplicationEventPublisher`):

```java
@Transactional
public void register(String email, String username, String rawPassword) {
  if (userService.usernameExists(username)) {
    throw new ApiException(ErrorCode.USERNAME_TAKEN, "That username is taken");
  }
  if (userService.emailExists(email)) {
    events.publishEvent(new DuplicateRegistrationAttempted(email));
    return; // enumeration-safe: same 202 as success
  }
  UserDto user = userService.createUnverified(email, username);
  credentialsService.createPassword(user.id(), rawPassword);
  organizationService.createPersonalOrganization(user.id(), username);
  String rawToken = emailVerificationService.issue(user.id());
  events.publishEvent(new VerificationEmailRequested(email, rawToken));
}
```

Events are records in `auth/services`; a `RegistrationEmailListener` component handles both with `@TransactionalEventListener(phase = AFTER_COMMIT)`, composing the mail: verification email subject "Verify your Zarlania account", body containing `https://zarlania.com/verify-email?token=<raw>` (base URL from config key `zarlania.auth.app-base-url`, default `${APP_BASE_URL:https://zarlania.com}` — add to `AuthProperties` and `application.yml`); duplicate-attempt email subject "Someone tried to register with your email". `verify(rawToken)`: `emailVerificationService.consume` → present → `userService.markEmailVerified`, return true; else false (controller maps false → `ApiException(INVALID_TOKEN, …)`). `resend(email)`: look up unverified user by email; if present issue + publish event; always return silently.

`AuthController` (register/verify/resend for now):

```java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final RegistrationService registrationService;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void register(@Valid @RequestBody RegisterRequest request) {
    registrationService.register(request.email(), request.username(), request.password());
  }

  @PostMapping("/verify")
  public void verify(@Valid @RequestBody VerifyRequest request) {
    if (!registrationService.verify(request.token())) {
      throw new ApiException(ErrorCode.INVALID_TOKEN, "Invalid or expired verification token");
    }
  }

  @PostMapping("/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resend(@Valid @RequestBody ResendRequest request) {
    registrationService.resend(request.email());
  }
}
```

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=RegistrationFlowTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api src/main/resources/application.yml
git commit -m "#<ISSUE> feat: registration with blocking email verification"
```

---

### Task 12: Login, refresh, logout

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/services/AuthTokenService.java`
- Modify: `src/main/java/com/zarlania/api/auth/controllers/AuthController.java`
- Create: `src/main/java/com/zarlania/api/auth/dtos/LoginRequest.java`, `TokenResponse.java`
- Test: `src/test/java/com/zarlania/api/auth/controllers/LoginFlowTest.java`

**Interfaces:**
- Produces: `TokenResponse(String accessToken)`; `AuthTokenService` with `record MintedSession(String accessToken, IssuedRefreshToken refresh)` and methods `MintedSession login(String identifier, String rawPassword)` (throws `ApiException(INVALID_CREDENTIALS)` uniformly for unknown identifier or bad password; `ApiException(EMAIL_UNVERIFIED)` when the password is right but the email unverified), `MintedSession refresh(String rawRefreshToken)` (maps `Invalid/ReusedRefreshTokenException` → `ApiException(INVALID_CREDENTIALS)` 401), `void logout(String rawRefreshToken)`. Cookie name: `zarlania_refresh`; attributes `HttpOnly`, `Secure` (from `authProperties.cookieSecure()`), `SameSite=Strict`, `Path=/auth`, `Max-Age` = seconds until `familyExpiresAt`. Token `kind` for user logins: `"user"` (string constant `TokenKinds.USER` in `auth/services/TokenKinds.java`, with `IMPERSONATION` and `SERVICE` reserved by spec 4).

- [ ] **Step 1: Failing flow test**

`LoginFlowTest` (same harness as Task 11): register+verify a user (helper method reusing the recording sender), then: login with username → 200, body has `accessToken` whose `org` claim equals the personal org id (decode with Nimbus), response has `Set-Cookie: zarlania_refresh=…; Path=/auth; HttpOnly; SameSite=Strict`; login with email → 200; wrong password → 401 `auth.invalid-credentials`; unknown identifier → 401 same code (indistinguishable); unverified user → 403 `auth.email-unverified`; refresh with the cookie → 200 new access token + rotated cookie value ≠ old; replaying the OLD cookie → 401 and then even the NEW cookie is dead (family revoked) → 401; logout with a live cookie → 204 and cookie cleared (`Max-Age=0`), subsequent refresh → 401; `/users/me` with the login's access token → 200.

- [ ] **Step 2: Implement**

`AuthTokenService` (deps: `UserService`, `CredentialsService`, `OrganizationService`, `RefreshTokenService`, `JwtService`):

```java
public MintedSession login(String identifier, String rawPassword) {
  UserDto user =
      userService
          .findByIdentifier(identifier)
          .filter(u -> credentialsService.passwordMatches(u.id(), rawPassword))
          .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, "Bad credentials"));
  if (!user.emailVerified()) {
    throw new ApiException(ErrorCode.EMAIL_UNVERIFIED, "Verify your email first");
  }
  OrganizationDto personal = organizationService.personalOrganizationOf(user.id()).orElseThrow();
  return mint(user.id(), personal.id());
}

private MintedSession mint(UUID userId, UUID organizationId) {
  IssuedRefreshToken refresh = refreshTokenService.startFamily(userId, organizationId);
  return new MintedSession(jwtService.mint(userId, organizationId, TokenKinds.USER), refresh);
}

public MintedSession refresh(String rawRefreshToken) {
  try {
    RefreshRotation rotation = refreshTokenService.rotate(rawRefreshToken);
    return new MintedSession(
        jwtService.mint(rotation.userId(), rotation.organizationId(), TokenKinds.USER),
        new IssuedRefreshToken(rotation.newRaw(), rotation.familyExpiresAt()));
  } catch (InvalidRefreshTokenException | ReusedRefreshTokenException e) {
    throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Refresh token rejected");
  }
}
```

Controller additions — cookie building in one private helper:

```java
  private ResponseCookie refreshCookie(IssuedRefreshToken refresh) {
    long maxAge = Duration.between(clock.instant(), refresh.familyExpiresAt()).toSeconds();
    return ResponseCookie.from(REFRESH_COOKIE, refresh.raw())
        .httpOnly(true)
        .secure(authProperties.cookieSecure())
        .sameSite("Strict")
        .path("/auth")
        .maxAge(maxAge)
        .build();
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    MintedSession session = authTokenService.login(request.identifier(), request.password());
    return withSession(session);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
    if (cookie == null) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "No refresh token");
    }
    return withSession(authTokenService.refresh(cookie));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
    if (cookie != null) {
      authTokenService.logout(cookie);
    }
    ResponseCookie cleared =
        ResponseCookie.from(REFRESH_COOKIE, "").path("/auth").maxAge(0).build();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cleared.toString())
        .build();
  }

  private ResponseEntity<TokenResponse> withSession(MintedSession session) {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refresh()).toString())
        .body(new TokenResponse(session.accessToken()));
  }
```

(`REFRESH_COOKIE = "zarlania_refresh"` constant; controller gains `Clock` and `AuthProperties` dependencies.) `LoginRequest(@NotBlank String identifier, @NotBlank String password)`.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test -Dtest=LoginFlowTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: login, cookie-borne refresh rotation, and logout"
```

---

### Task 13: Rate limiting

**Files:**
- Create: `src/main/java/com/zarlania/api/common/throttle/RateLimiter.java`
- Create: `src/main/java/com/zarlania/api/common/throttle/InMemoryRateLimiter.java`
- Create: `src/main/java/com/zarlania/api/common/throttle/ThrottleProperties.java`
- Modify: `src/main/java/com/zarlania/api/auth/controllers/AuthController.java`
- Test: `src/test/java/com/zarlania/api/common/throttle/InMemoryRateLimiterTest.java`

**Interfaces:**
- Produces: `RateLimiter { boolean tryConsume(String key, int limit); }` — fixed one-minute window (window length in `ThrottleProperties(Duration window, int loginLimit, int registerLimit, int resendLimit, int refreshLimit)`, prefix `zarlania.throttle`, defaults `PT1M`, 10, 5, 3, 30). `InMemoryRateLimiter` is the only implementation (single instance today; a Redis adapter is the interface's future — spec rationale). Controller wiring: each auth endpoint first calls `requireCapacity(limitName, request)` with key `<endpoint>:<client-ip>` (IP from `HttpServletRequest.getRemoteAddr()`), throwing `ApiException(THROTTLED)` on false.

- [ ] **Step 1: Failing unit test**

`InMemoryRateLimiterTest` (constructed with a `MutableClock` — a tiny test double class in the test file implementing `Clock` with a settable instant): limit 3 → three `tryConsume("k", 3)` true, fourth false; advancing the clock past the window resets; keys are independent.

- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
public class InMemoryRateLimiter implements RateLimiter {

  private record Window(Instant start, AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final ThrottleProperties properties;
  private final Clock clock;

  @Override
  public boolean tryConsume(String key, int limit) {
    Instant now = clock.instant();
    Window window =
        windows.compute(
            key,
            (k, existing) ->
                existing == null || now.isAfter(existing.start().plus(properties.window()))
                    ? new Window(now, new AtomicInteger())
                    : existing);
    return window.count().incrementAndGet() <= limit;
  }
}
```

Wire into `AuthController` (`register`, `resend`, `login`, `refresh`); extend `LoginFlowTest` with one case: 11 rapid logins with a wrong password → the 11th returns 429 `auth.throttled`.

- [ ] **Step 3: Pass → commit**

```bash
./mvnw test && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api src/main/resources/application.yml
git commit -m "#<ISSUE> feat: throttle the auth endpoints in-memory"
```

---

### Task 14: Unverified-account cleanup

**Files:**
- Create: `src/main/java/com/zarlania/api/auth/services/UnverifiedAccountCleanup.java`
- Modify: `src/main/java/com/zarlania/api/ZarlaniaApiApplication.java` (add `@EnableScheduling`; keep `@ConfigurationPropertiesScan` from Task 8 if placed here)
- Test: `src/test/java/com/zarlania/api/auth/services/UnverifiedAccountCleanupTest.java` (integration)

**Interfaces:**
- Consumes: `UserRepository.findByEmailVerifiedAtIsNullAndCreatedAtBefore`, plus deletion methods added here: `PasswordCredentialRepository.deleteByUserId(UUID)`, `EmailVerificationTokenRepository.deleteByUserId(UUID)`, `MembershipRepository.deleteByUserId(UUID)`, and `OrganizationService.deletePersonalOrganizationOf(UUID userId)` (new method: deletes the personal org after its membership).
- Produces: `@Scheduled(fixedDelayString = "${zarlania.auth.cleanup-interval:PT1H}") void purgeExpiredUnverifiedAccounts()` — deletes, per expired user (older than `unverifiedAccountMaxAge`, unverified): verification tokens, credential, membership + personal org, then the user, in one transaction per user.

- [ ] **Step 1: Failing integration test**

Insert one unverified user (with credential, personal org, verification token) whose `created_at` is 8 days old — since `createdAt` is Hibernate-managed, set it directly with `jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", …)` after saving; insert one fresh unverified and one verified user. Call `purgeExpiredUnverifiedAccounts()` directly. Assert: expired user + credential + org + membership + tokens gone; the other two users intact.

- [ ] **Step 2: Implement, pass, commit**

```bash
./mvnw test -Dtest=UnverifiedAccountCleanupTest && ./mvnw spotless:apply
git add src/main/java/com/zarlania/api src/test/java/com/zarlania/api
git commit -m "#<ISSUE> feat: purge unverified accounts after seven days"
```

---

### Task 15: End-to-end journey test

**Files:**
- Test: `src/test/java/com/zarlania/api/AuthJourneyTest.java`

One MockMvc test walking the whole story in order (single test method is fine — it is a journey, not a unit): register → 202 → extract token from recorded email → login before verify → 403 `auth.email-unverified` → verify → 200 → login → 200 (capture access token + cookie) → `GET /users/me` → 200 with the username and `PERSONAL` org → refresh → new cookie → replay old cookie → 401 → new cookie also dead → 401 → login again → logout → refresh → 401.

- [ ] **Step 1: Write it, run it, commit**

```bash
./mvnw test -Dtest=AuthJourneyTest
git add src/test/java/com/zarlania/api/AuthJourneyTest.java
git commit -m "#<ISSUE> test: cover the full registration-to-logout journey"
```

---

### Task 16: Deployment config, docs, verification, PR

**Files:**
- Modify: `render.yaml`, `.env.example`, `CLAUDE.md` (Commands note only if needed)
- Create/Modify: reference docs via skills

- [ ] **Step 1: Render env vars**

Add to the service's `envVars` in `render.yaml` (all `sync: false` — secrets set in the dashboard):

```yaml
      - key: RESEND_API_KEY
        sync: false
      - key: JWT_PRIVATE_KEY
        sync: false
      - key: JWT_RETIRED_PUBLIC_KEYS
        sync: false
```

Append to `.env.example`:

```bash
# Auth (local): leave JWT_PRIVATE_KEY unset to use an ephemeral dev keypair.
# Generate a real one with:
#   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048
#JWT_PRIVATE_KEY=
# Leave unset locally: emails are logged, not sent.
#RESEND_API_KEY=
# Cookies must not require HTTPS against a local http:// frontend.
AUTH_COOKIE_SECURE=false
```

- [ ] **Step 2: Reference docs**

Invoke `updating-reference-docs` for the persistence doc if any convention drifted, and `creating-reference-docs` for a new **Authentication and tokens** doc covering: the five domains and their boundaries; the token model table (user JWT claims `iss/sub/org/kind/jti/iat/exp`, 15-min TTL; opaque refresh tokens, 30-day absolute families, rotation + reuse revocation; cookie attributes); Argon2 parameters; JWKS + key rotation runbook (generate, set `JWT_PRIVATE_KEY`, move old public into `JWT_RETIRED_PUBLIC_KEYS`, drop after 15 min); email verification flow incl. enumeration stances; the manual setup steps (Resend account, SPF/DKIM on zarlania.com, Render env vars); throttling defaults.

- [ ] **Step 3: Full gates + PR**

```bash
./mvnw verify
yamllint --strict -c .yamllint.yml .
npx markdownlint-cli2
python3 docs/tooling/references_cli.py validate
git push -u origin <ISSUE>-core-auth
gh pr create --title "#<ISSUE> feat: account registration, login, and token refresh" --label minor --body "$(cat <<'EOF'
Implements docs/superpowers/specs/2026-07-25-users-personal-orgs-core-auth-design.md — users/organizations/credentials/auth domains, Argon2id passwords, blocking email verification, RS256 JWTs + JWKS, rotating refresh families with reuse detection, throttling, and the unverified-account cleanup.

Closes #<ISSUE>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: all gates green; PR Lint passes (`minor` label — new user-facing capability).

---

## Self-Review (completed at authoring)

- **Spec coverage:** endpoints table → Tasks 9 (`/users/me`, JWKS), 11 (register/verify/resend), 12 (login/refresh/logout); domains → 3–7, 10; enumeration rules → 11; cookie attrs + uniform 401 → 12; throttling → 13; cleanup → 14; keys/rotation/dev-ephemeral → 8; Resend + fail-fast prod → 6; config/env + docs + manual steps → 16; journey → 15. OAuth seam = `AuthTokenService.mint(userId, orgId)` being independent of how the user authenticated.
- **Placeholders:** none; `<ISSUE>` defined in Task 0.
- **Type consistency:** `AuthProperties` record fields match YAML keys (relaxed binding maps `jwt-private-key-pem` ↔ `jwtPrivateKeyPem`); `IssuedRefreshToken`/`RefreshRotation`/`MintedSession` shapes align across Tasks 10 and 12; `TokenKinds.USER` string `"user"` matches the spec's `kind` claim; `RecordingEmailSender` used by Tasks 11, 12, 15.
