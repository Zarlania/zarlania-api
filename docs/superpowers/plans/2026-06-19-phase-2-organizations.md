# Phase 2 — `organizations` Domain — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `organizations` domain — `Organization` + `Membership` entities, repositories, DTOs, and a service that enforces every ownership invariant — on top of the Phase 1 persistence foundation, with the database FK `memberships.user_id` → `users.id` for integrity at rest and **no compile-time dependency on the `users` domain**.

**Architecture:** A second feature domain `com.zarlania.api.organizations`, structured exactly like the merged `users` domain: layer sub-packages (`entity`, `dto`, `repository`, `service`, `exception`) plus two domain-root enums. Entities stay internal; the only types crossing the boundary are the `Organization` / `Membership` record DTOs and opaque `UUID`s. `organizations` references a user only by an opaque `userId` column — it never imports or loads `UserEntity` — while a real Flyway-declared foreign key protects referential integrity at the database layer (ADR-0011). All invariants are enforced in `OrganizationService` and proven through its public surface.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Maven (`./mvnw`), Spring Data JPA, H2, Flyway, Lombok, JUnit 5, AssertJ, Mockito.

This plan implements **Phase 2 only** of `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md`. Phase 1 (persistence + `users`) is merged (v0.2.0); Phase 3 (reference-docs system) is planned separately.

## Global Constraints

Every task implicitly includes these (exact values from the spec and the merged Phase 1 code):

- **Java 25 / Spring Boot 4.1.0 / Maven wrapper `./mvnw`.** No new dependencies this phase — the persistence stack (`spring-boot-starter-data-jpa`, H2, `flyway-core`, Lombok) is already on the classpath. Do **not** add or pin dependency versions.
- **Flyway owns the schema** (`src/main/resources/db/migration`); Hibernate runs **`ddl-auto=validate`**, never generates DDL. The new migration is `V2__create_organizations_and_memberships.sql` (V1 already exists).
- **No cross-domain entity associations.** `organizations` stores `userId` as a plain `UUID` column and **never imports `com.zarlania.api.users.*`** in `src/main`. The link to users is the database FK only (ADR-0011). A `@ManyToOne` from `Membership` to `Organization` is allowed — it is *same-domain*.
- **Entities use Lombok** `@Getter` / `@NoArgsConstructor`, with `@Setter` on individual mutable fields (matching `UserEntity`). **NEVER `@Data` or `@EqualsAndHashCode` on a JPA entity** (ADR-0012).
- **DTOs are Java `records`** named with the **canonical domain noun** (`Organization`, `Membership`) — no Lombok. The entities are suffixed (`OrganizationEntity`, `MembershipEntity`). Mapping lives in a `@Component` mapper in the service layer, never on the DTO or entity (matches `UserMapper`).
- **Domain exceptions** follow the `EmailAlreadyExistsException` pattern: a `RuntimeException` with a `private` constructor, a `public static` factory, `@Getter` structured fields, and a message that does **not** embed user data.
- **Audit timestamps** are inherited from `Auditable` (`Instant`, microsecond precision, no truncation) — extend it; never declare `createdAt`/`updatedAt` yourself.
- **No HTTP endpoints / controllers** this phase (deferred by the spec) and **no new ADRs** (ADRs 0010–0012 already cover persistence, domain boundary, and Lombok; the 4th is Phase 3). Phase 2 only *applies* those decisions.
- **Tests:** AssertJ for all assertions; Mockito (`@ExtendWith(MockitoExtension.class)`) for the pure-logic unit test; integration tests use `@DataJpaTest` against **H2 with the real Flyway-migrated schema**, pinned with `@TestPropertySource`. **Assert observable behavior through the public service surface, not mock interactions.** Tests are package-private (no Javadoc needed). Because the `memberships.user_id` FK is real, any test that creates a membership must first seed a `users` row via native SQL (helper given below) — keeping the `organizations` tests free of any `users`-domain import.
- **Coverage:** the build enforces **≥ 80% line and branch** (JaCoCo). Lombok-generated and record methods are excluded via `lombok.config` / are trivial. Cover every rejection branch.
- **Javadoc:** every **public** type and public method needs Javadoc (Checkstyle `google_checks`, `warning` severity fails the build). Keep test classes/methods **package-private**.
- **Formatting:** run `./mvnw -q spotless:apply` before every commit (google-java-format).
- **Workflow:** work on branch `feat/<issue#>-phase-2-organizations`; PR title references `#<issue>`; apply the **`release:minor`** label; bump `pom.xml` `0.2.0 → 0.3.0` with `./scripts/bump-version bump minor` inside the PR.
- **Every commit message ends with the trailer:** `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## File Structure

New domain `com.zarlania.api.organizations`, mirroring the merged `users` layout:

- `src/main/resources/db/migration/V2__create_organizations_and_memberships.sql` — `organizations` + `memberships` tables, the FK `memberships.user_id` → `users.id`, the `(organization_id, user_id)` unique constraint, and `CHECK` constraints for the enums (create).
- `com.zarlania.api.organizations.OrganizationType` — enum `PERSONAL | GENERAL` (create).
- `com.zarlania.api.organizations.MembershipRole` — enum `OWNER | MEMBER` (create).
  - *Both enums live at the **domain root**, not in a layer sub-package: they are layer-neutral domain value types referenced by **both** the entity and the DTO packages, so neither layer should own them.*
- `com.zarlania.api.organizations.entity.OrganizationEntity` — JPA entity (create).
- `com.zarlania.api.organizations.entity.MembershipEntity` — JPA entity; same-domain `@ManyToOne` to `OrganizationEntity`, plain `userId` column (create).
- `com.zarlania.api.organizations.repository.OrganizationRepository` — `JpaRepository<OrganizationEntity, UUID>` (create).
- `com.zarlania.api.organizations.repository.MembershipRepository` — `JpaRepository<MembershipEntity, UUID>` with derived queries (create).
- `com.zarlania.api.organizations.dto.Organization` — boundary record (create).
- `com.zarlania.api.organizations.dto.Membership` — boundary record (create).
- `com.zarlania.api.organizations.service.OrganizationMapper` — `@Component`, entity→DTO (create).
- `com.zarlania.api.organizations.service.OrganizationService` — the public surface; all invariants (create).
- `com.zarlania.api.organizations.exception.OrganizationNotFoundException` (create).
- `com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException` (create).
- `com.zarlania.api.organizations.exception.PersonalOrganizationMembershipException` (create).
- `com.zarlania.api.organizations.exception.DuplicateMembershipException` (create).
- `com.zarlania.api.organizations.support.OrganizationTestSupport` (**test tree**) — public helper that seeds `users` rows via native SQL so the `memberships.user_id` FK is satisfied without importing the `users` domain. Single source of the seed logic, shared by the repository and service tests (create in Task 2).
- Other tests mirror the production classes under `src/test/java/...` (schema, repository, mapper, service-integration, service-unit).

The merged `users` domain, `Auditable`, `JpaConfig`, `application.properties`, and `V1` are **not modified**.

---

## Setup (before Task 1)

Do this once, at execution start, in an isolated workspace (use the superpowers:using-git-worktrees skill).

- [ ] **Create the Phase 2 issue and capture its number:**

```bash
gh issue create \
  --title "Phase 2: organizations domain" \
  --label enhancement --label "release:minor" \
  --body "Implements Phase 2 of the users-and-organizations spec: the organizations domain (Organization + Membership entities, repositories, DTOs, and OrganizationService with all ownership invariants), the V2 migration with the memberships.user_id -> users.id FK, and full tests. No new ADRs. Spec: docs/superpowers/specs/2026-06-17-users-and-organizations-design.md"
```

Note the printed issue number; call it `<issue#>` below.

- [ ] **Create the branch off up-to-date `master`:**

```bash
git checkout master && git pull
git checkout -b feat/<issue#>-phase-2-organizations
```

---

## Task 1: V2 migration — `organizations` + `memberships` tables

Add the second Flyway migration and prove the context boots with both tables created, including the FK and constraints. Setup-heavy, so the deliverable is verified by a boot/schema test rather than written strictly test-first.

**Files:**
- Create: `src/main/resources/db/migration/V2__create_organizations_and_memberships.sql`
- Test: `src/test/java/com/zarlania/api/organizations/OrganizationsSchemaTest.java`

**Interfaces:**
- Consumes: the `users` table + Flyway/H2 wiring from Phase 1.
- Produces: the `organizations` table (`id`, `name`, `type`, audit cols) and the `memberships` table (`id`, `organization_id` FK→organizations, `user_id` FK→users, `role`, audit cols) with `uq_memberships_org_user (organization_id, user_id)`. Task 2's entities map to these.

- [ ] **Step 1: Create the migration `src/main/resources/db/migration/V2__create_organizations_and_memberships.sql`.**

```sql
CREATE TABLE organizations (
    id         UUID                        NOT NULL,
    name       VARCHAR(200)                NOT NULL,
    type       VARCHAR(20)                 NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_organizations   PRIMARY KEY (id),
    CONSTRAINT ck_organizations_type CHECK (type IN ('PERSONAL', 'GENERAL'))
);

CREATE TABLE memberships (
    id              UUID                        NOT NULL,
    organization_id UUID                        NOT NULL,
    user_id         UUID                        NOT NULL,
    role            VARCHAR(20)                 NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_memberships              PRIMARY KEY (id),
    CONSTRAINT fk_memberships_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_memberships_user         FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_memberships_org_user     UNIQUE (organization_id, user_id),
    CONSTRAINT ck_memberships_role         CHECK (role IN ('OWNER', 'MEMBER'))
);
```

- [ ] **Step 2: Write the boot/schema test `src/test/java/com/zarlania/api/organizations/OrganizationsSchemaTest.java`.**

```java
package com.zarlania.api.organizations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OrganizationsSchemaTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flywayCreatesEmptyOrganizationsTable() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void flywayCreatesEmptyMembershipsTable() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memberships", Integer.class);
    assertThat(count).isZero();
  }
}
```

- [ ] **Step 3: Format, then run the schema test.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=OrganizationsSchemaTest`
Expected: PASS. The Spring context boots, Flyway applies `V1` then `V2`, and both new tables exist and are empty. If Flyway reports a checksum/order error, confirm the file name is exactly `V2__create_organizations_and_memberships.sql` and that `V1` is unchanged.

- [ ] **Step 4: Commit.**

```bash
git add src/main/resources/db/migration/V2__create_organizations_and_memberships.sql \
  src/test/java/com/zarlania/api/organizations/OrganizationsSchemaTest.java
git commit -m "feat: add V2 migration for organizations and memberships tables

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Enums, entities, and repositories

Add the two domain enums, the `OrganizationEntity` and `MembershipEntity` mapped to the V2 tables, and their repositories. Prove persistence assigns ids + audit timestamps, that the derived queries work, and that the unique `(organization_id, user_id)` constraint and the `user_id` FK both bite. This task is where `ddl-auto=validate` confirms the entities agree with the migrated schema.

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/OrganizationType.java`
- Create: `src/main/java/com/zarlania/api/organizations/MembershipRole.java`
- Create: `src/main/java/com/zarlania/api/organizations/entity/OrganizationEntity.java`
- Create: `src/main/java/com/zarlania/api/organizations/entity/MembershipEntity.java`
- Create: `src/main/java/com/zarlania/api/organizations/repository/OrganizationRepository.java`
- Create: `src/main/java/com/zarlania/api/organizations/repository/MembershipRepository.java`
- Create (test support): `src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java`
- Test: `src/test/java/com/zarlania/api/organizations/repository/MembershipRepositoryTest.java`

**Interfaces:**
- Consumes: the `organizations` / `memberships` tables from Task 1; `Auditable` from Phase 1.
- Produces (for Task 4 too): `OrganizationTestSupport.seedUser(EntityManager entityManager, String email)` → `UUID` — inserts a `users` row with a generated id and returns that id. The single source of the FK-seed logic; the service test in Task 4 reuses it.
- Produces:
  - `OrganizationType` — enum constants `PERSONAL`, `GENERAL`.
  - `MembershipRole` — enum constants `OWNER`, `MEMBER`.
  - `OrganizationEntity extends Auditable` — `UUID getId()`, `String getName()` / `setName`, `OrganizationType getType()` / `setType`, no-arg constructor.
  - `MembershipEntity extends Auditable` — `UUID getId()`, `OrganizationEntity getOrganization()` / `setOrganization`, `UUID getUserId()` / `setUserId`, `MembershipRole getRole()` / `setRole`, no-arg constructor.
  - `OrganizationRepository extends JpaRepository<OrganizationEntity, UUID>`.
  - `MembershipRepository extends JpaRepository<MembershipEntity, UUID>` with: `List<MembershipEntity> findByOrganization_Id(UUID organizationId)`; `boolean existsByOrganization_IdAndUserId(UUID organizationId, UUID userId)`; `Optional<MembershipEntity> findByOrganization_IdAndUserId(UUID organizationId, UUID userId)`; `boolean existsByUserIdAndRoleAndOrganization_Type(UUID userId, MembershipRole role, OrganizationType type)`.

- [ ] **Step 1a: Create the shared test support helper `src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java`.**

This is the single source of the `users`-seed logic, reused by this task's repository test and Task 4's service test. It is `public` because the tests live in sibling packages; it imports nothing from the `users` domain (raw native SQL only).

```java
package com.zarlania.api.organizations.support;

import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Test support for the {@code organizations} domain. Seeds {@code users} rows directly via native
 * SQL so the {@code memberships.user_id} foreign key is satisfied, keeping the organizations tests
 * free of any compile-time dependency on the {@code users} domain.
 */
public final class OrganizationTestSupport {

  private OrganizationTestSupport() {}

  /**
   * Inserts a {@code users} row with a freshly generated id and the given email.
   *
   * @param entityManager the JPA entity manager bound to the test's transaction
   * @param email the unique email for the seeded user
   * @return the generated id of the seeded user
   */
  public static UUID seedUser(EntityManager entityManager, String email) {
    UUID id = UUID.randomUUID();
    entityManager
        .createNativeQuery(
            "INSERT INTO users (id, email, display_name, created_at, updated_at) "
                + "VALUES (?1, ?2, ?3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "Seed " + email)
        .executeUpdate();
    return id;
  }
}
```

- [ ] **Step 1b: Write the failing repository test `src/test/java/com/zarlania/api/organizations/repository/MembershipRepositoryTest.java`.**

It delegates the FK-seed to `OrganizationTestSupport.seedUser` (single-source logic) via a one-line local binder that supplies the test's entity manager.

```java
package com.zarlania.api.organizations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.organizations.support.OrganizationTestSupport;
import com.zarlania.api.persistence.JpaConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class MembershipRepositoryTest {

  @Autowired private OrganizationRepository organizations;
  @Autowired private MembershipRepository memberships;
  @Autowired private TestEntityManager entityManager;

  // Local binder over the shared seed helper (the SQL lives once in OrganizationTestSupport).
  private UUID seedUser(String email) {
    return OrganizationTestSupport.seedUser(entityManager.getEntityManager(), email);
  }

  private OrganizationEntity saveOrganization(String name, OrganizationType type) {
    OrganizationEntity org = new OrganizationEntity();
    org.setName(name);
    org.setType(type);
    return organizations.save(org);
  }

  private MembershipEntity newMembership(OrganizationEntity org, UUID userId, MembershipRole role) {
    MembershipEntity membership = new MembershipEntity();
    membership.setOrganization(org);
    membership.setUserId(userId);
    membership.setRole(role);
    return membership;
  }

  @Test
  void savingAssignsIdAndAuditTimestamps() {
    UUID userId = seedUser("owner@example.com");
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);

    MembershipEntity saved = memberships.save(newMembership(org, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByOrganizationIdReturnsItsMemberships() {
    UUID ownerId = seedUser("owner2@example.com");
    UUID memberId = seedUser("member2@example.com");
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);
    memberships.save(newMembership(org, ownerId, MembershipRole.OWNER));
    memberships.save(newMembership(org, memberId, MembershipRole.MEMBER));
    entityManager.flush();

    List<MembershipEntity> found = memberships.findByOrganization_Id(org.getId());

    assertThat(found).hasSize(2);
  }

  @Test
  void existsByOrganizationAndUserReflectsState() {
    UUID userId = seedUser("owner3@example.com");
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);

    assertThat(memberships.existsByOrganization_IdAndUserId(org.getId(), userId)).isFalse();
    memberships.save(newMembership(org, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThat(memberships.existsByOrganization_IdAndUserId(org.getId(), userId)).isTrue();
  }

  @Test
  void existsByUserRoleAndOrgTypeDetectsAPersonalOwner() {
    UUID userId = seedUser("owner4@example.com");
    OrganizationEntity personal = saveOrganization("Mine", OrganizationType.PERSONAL);
    memberships.save(newMembership(personal, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThat(
            memberships.existsByUserIdAndRoleAndOrganization_Type(
                userId, MembershipRole.OWNER, OrganizationType.PERSONAL))
        .isTrue();
    assertThat(
            memberships.existsByUserIdAndRoleAndOrganization_Type(
                UUID.randomUUID(), MembershipRole.OWNER, OrganizationType.PERSONAL))
        .isFalse();
  }

  @Test
  void duplicateMembershipForSameUserAndOrgViolatesUniqueConstraint() {
    UUID userId = seedUser("owner5@example.com");
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);
    memberships.save(newMembership(org, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThatThrownBy(
            () -> memberships.saveAndFlush(newMembership(org, userId, MembershipRole.MEMBER)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void membershipForUnknownUserViolatesTheForeignKey() {
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);

    assertThatThrownBy(
            () ->
                memberships.saveAndFlush(
                    newMembership(org, UUID.randomUUID(), MembershipRole.MEMBER)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails.**

Run: `./mvnw -q test -Dtest=MembershipRepositoryTest`
Expected: FAIL — compilation error (the enums, entities, and repositories do not exist yet).

- [ ] **Step 3: Create `OrganizationType.java`.**

```java
package com.zarlania.api.organizations;

/** The kind of organization: a user's private {@code PERSONAL} space or a shared {@code GENERAL} (company) account. */
public enum OrganizationType {
  /** A user's single personal organization: exactly one owner and no other members. */
  PERSONAL,
  /** A shared organization that may have multiple owners and members. */
  GENERAL
}
```

- [ ] **Step 4: Create `MembershipRole.java`.**

```java
package com.zarlania.api.organizations;

/** A user's role within an organization. */
public enum MembershipRole {
  /** Full control of the organization. Every organization always has at least one owner. */
  OWNER,
  /** A non-owner participant in a {@code GENERAL} organization. */
  MEMBER
}
```

- [ ] **Step 5: Create `OrganizationEntity.java`.**

```java
package com.zarlania.api.organizations.entity;

import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An organization: the ownership root for data in the system. Internal to the {@code organizations}
 * domain; crosses boundaries via the {@link com.zarlania.api.organizations.dto.Organization} DTO.
 */
@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor
public class OrganizationEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 20)
  private OrganizationType type;
}
```

- [ ] **Step 6: Create `MembershipEntity.java`.**

```java
package com.zarlania.api.organizations.entity;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's membership in an organization. The {@code organization} association is same-domain; the
 * {@code userId} is a plain column — never a JPA relationship to the {@code users} domain (ADR-0011).
 * Referential integrity for {@code userId} is enforced by the {@code memberships.user_id} → {@code
 * users.id} foreign key declared in the V2 migration, not by an ORM association.
 */
@Entity
@Table(name = "memberships")
@Getter
@NoArgsConstructor
public class MembershipEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "organization_id", nullable = false)
  private OrganizationEntity organization;

  @Setter
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private MembershipRole role;
}
```

- [ ] **Step 7: Create `OrganizationRepository.java`.**

```java
package com.zarlania.api.organizations.repository;

import com.zarlania.api.organizations.entity.OrganizationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link OrganizationEntity}. Internal to the {@code organizations} domain. */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}
```

- [ ] **Step 8: Create `MembershipRepository.java`.**

```java
package com.zarlania.api.organizations.repository;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.MembershipEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link MembershipEntity}. Internal to the {@code organizations} domain. */
public interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> {

  /**
   * Lists all memberships of an organization.
   *
   * @param organizationId the organization id
   * @return the organization's memberships (empty if none)
   */
  List<MembershipEntity> findByOrganization_Id(UUID organizationId);

  /**
   * Reports whether the given user already has a membership in the given organization.
   *
   * @param organizationId the organization id
   * @param userId the user id
   * @return {@code true} if a membership already exists for that user in that organization
   */
  boolean existsByOrganization_IdAndUserId(UUID organizationId, UUID userId);

  /**
   * Finds the given user's membership in the given organization, if any.
   *
   * @param organizationId the organization id
   * @param userId the user id
   * @return the membership, if one exists
   */
  Optional<MembershipEntity> findByOrganization_IdAndUserId(UUID organizationId, UUID userId);

  /**
   * Reports whether the user holds the given role in an organization of the given type — used to
   * enforce the "one personal organization per user" rule.
   *
   * @param userId the user id
   * @param role the membership role to match
   * @param type the organization type to match
   * @return {@code true} if such a membership exists
   */
  boolean existsByUserIdAndRoleAndOrganization_Type(
      UUID userId, MembershipRole role, OrganizationType type);
}
```

- [ ] **Step 9: Format, then run the repository test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=MembershipRepositoryTest`
Expected: PASS (all six tests). If Hibernate `validate` rejects a column, the entity mapping and the V2 column type/name disagree — fix the mapping to match the migration (keep `ddl-auto=validate`; never drop to `none`). If a native `INSERT` into `users` fails on the UUID parameter, the column binding is fine in H2/PostgreSQL mode — re-check the column list matches `V1` (`id, email, display_name, created_at, updated_at`).

- [ ] **Step 10: Commit.**

```bash
git add src/main/java/com/zarlania/api/organizations/OrganizationType.java \
  src/main/java/com/zarlania/api/organizations/MembershipRole.java \
  src/main/java/com/zarlania/api/organizations/entity/OrganizationEntity.java \
  src/main/java/com/zarlania/api/organizations/entity/MembershipEntity.java \
  src/main/java/com/zarlania/api/organizations/repository/OrganizationRepository.java \
  src/main/java/com/zarlania/api/organizations/repository/MembershipRepository.java \
  src/test/java/com/zarlania/api/organizations/support/OrganizationTestSupport.java \
  src/test/java/com/zarlania/api/organizations/repository/MembershipRepositoryTest.java
git commit -m "feat: add organization/membership enums, entities and repositories

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Boundary DTOs and the mapper

Add the `Organization` and `Membership` record DTOs (canonical names) and the `OrganizationMapper` that converts entities to them — the only shapes that leave the domain.

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/dto/Organization.java`
- Create: `src/main/java/com/zarlania/api/organizations/dto/Membership.java`
- Create: `src/main/java/com/zarlania/api/organizations/service/OrganizationMapper.java`
- Test: `src/test/java/com/zarlania/api/organizations/service/OrganizationMapperTest.java` (pure unit)

**Interfaces:**
- Consumes: `OrganizationEntity`, `MembershipEntity`, `OrganizationType`, `MembershipRole` from Task 2.
- Produces:
  - `Organization(UUID id, String name, OrganizationType type)`.
  - `Membership(UUID organizationId, UUID userId, MembershipRole role)`.
  - `OrganizationMapper` (`@Component`) — `Organization toDto(OrganizationEntity)`, `Membership toDto(MembershipEntity)`.

- [ ] **Step 1: Write the failing mapper test `src/test/java/com/zarlania/api/organizations/service/OrganizationMapperTest.java`.**

```java
package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import org.junit.jupiter.api.Test;

class OrganizationMapperTest {

  private final OrganizationMapper mapper = new OrganizationMapper();

  @Test
  void mapsOrganizationEntityToDto() {
    OrganizationEntity entity = new OrganizationEntity();
    entity.setName("Acme");
    entity.setType(OrganizationType.GENERAL);

    Organization dto = mapper.toDto(entity);

    assertThat(dto.id()).isEqualTo(entity.getId());
    assertThat(dto.name()).isEqualTo("Acme");
    assertThat(dto.type()).isEqualTo(OrganizationType.GENERAL);
  }

  @Test
  void mapsMembershipEntityToDtoUsingOrganizationId() {
    OrganizationEntity org = new OrganizationEntity();
    org.setName("Acme");
    org.setType(OrganizationType.GENERAL);

    MembershipEntity entity = new MembershipEntity();
    entity.setOrganization(org);
    entity.setUserId(java.util.UUID.randomUUID());
    entity.setRole(MembershipRole.OWNER);

    Membership dto = mapper.toDto(entity);

    assertThat(dto.organizationId()).isEqualTo(org.getId());
    assertThat(dto.userId()).isEqualTo(entity.getUserId());
    assertThat(dto.role()).isEqualTo(MembershipRole.OWNER);
  }
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `./mvnw -q test -Dtest=OrganizationMapperTest`
Expected: FAIL — compilation error (`Organization`, `Membership`, `OrganizationMapper` do not exist).

- [ ] **Step 3: Create `Organization.java`.**

```java
package com.zarlania.api.organizations.dto;

import com.zarlania.api.organizations.OrganizationType;
import java.util.UUID;

/**
 * Immutable view of an organization for use across the domain boundary and in API responses. This
 * DTO — not the JPA {@code OrganizationEntity} — is the type passed throughout the application.
 *
 * @param id the organization's id
 * @param name the organization's display name
 * @param type whether the organization is {@code PERSONAL} or {@code GENERAL}
 */
public record Organization(UUID id, String name, OrganizationType type) {}
```

- [ ] **Step 4: Create `Membership.java`.**

```java
package com.zarlania.api.organizations.dto;

import com.zarlania.api.organizations.MembershipRole;
import java.util.UUID;

/**
 * Immutable view of a user's membership in an organization, referencing both by opaque id. This DTO
 * — not the JPA {@code MembershipEntity} — is the type passed throughout the application.
 *
 * @param organizationId the organization the membership belongs to
 * @param userId the member's user id
 * @param role the member's role in the organization
 */
public record Membership(UUID organizationId, UUID userId, MembershipRole role) {}
```

- [ ] **Step 5: Create `OrganizationMapper.java`.**

```java
package com.zarlania.api.organizations.service;

import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import org.springframework.stereotype.Component;

/** Maps {@code organizations} entities to their DTOs for crossing the domain boundary. */
@Component
public class OrganizationMapper {

  /**
   * Maps an organization entity to its DTO.
   *
   * @param entity the source entity
   * @return a DTO carrying the entity's id, name, and type
   */
  public Organization toDto(OrganizationEntity entity) {
    return new Organization(entity.getId(), entity.getName(), entity.getType());
  }

  /**
   * Maps a membership entity to its DTO, exposing only the organization's id (never the entity).
   *
   * @param entity the source entity
   * @return a DTO carrying the organization id, user id, and role
   */
  public Membership toDto(MembershipEntity entity) {
    return new Membership(entity.getOrganization().getId(), entity.getUserId(), entity.getRole());
  }
}
```

- [ ] **Step 6: Format, then run the mapper test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=OrganizationMapperTest`
Expected: PASS (both tests).

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/com/zarlania/api/organizations/dto/Organization.java \
  src/main/java/com/zarlania/api/organizations/dto/Membership.java \
  src/main/java/com/zarlania/api/organizations/service/OrganizationMapper.java \
  src/test/java/com/zarlania/api/organizations/service/OrganizationMapperTest.java
git commit -m "feat: add Organization/Membership DTOs and OrganizationMapper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: `OrganizationService` and the domain exceptions (all invariants)

Add the four domain exceptions and the service that creates personal/general organizations, adds members and owners, exposes reads, and enforces every spec invariant. This is the public surface of the domain. Drive it with an integration test (real H2 schema) for the invariants and a pure-unit test (Mockito) for the input-validation guards.

**Files:**
- Create: `src/main/java/com/zarlania/api/organizations/exception/OrganizationNotFoundException.java`
- Create: `src/main/java/com/zarlania/api/organizations/exception/PersonalOrganizationAlreadyExistsException.java`
- Create: `src/main/java/com/zarlania/api/organizations/exception/PersonalOrganizationMembershipException.java`
- Create: `src/main/java/com/zarlania/api/organizations/exception/DuplicateMembershipException.java`
- Create: `src/main/java/com/zarlania/api/organizations/service/OrganizationService.java`
- Test: `src/test/java/com/zarlania/api/organizations/service/OrganizationServiceTest.java` (integration)
- Test: `src/test/java/com/zarlania/api/organizations/service/OrganizationServiceUnitTest.java` (pure unit)

**Interfaces:**
- Consumes: `OrganizationRepository`, `MembershipRepository`, `OrganizationMapper`, the enums, entities, and DTOs from Tasks 2–3.
- Produces:
  - `OrganizationNotFoundException` — `static forId(UUID)`, `UUID getOrganizationId()`.
  - `PersonalOrganizationAlreadyExistsException` — `static forOwner(UUID)`, `UUID getOwnerUserId()`.
  - `PersonalOrganizationMembershipException` — `static forOrganization(UUID)`, `UUID getOrganizationId()`.
  - `DuplicateMembershipException` — `static forMembership(UUID organizationId, UUID userId)`, `UUID getOrganizationId()`, `UUID getUserId()`.
  - `OrganizationService` — `Organization createPersonalOrganization(UUID ownerUserId, String name)`, `Organization createGeneralOrganization(UUID creatorUserId, String name)`, `Membership addMember(UUID organizationId, UUID userId)`, `Membership addOwner(UUID organizationId, UUID userId)`, `Optional<Organization> findById(UUID id)`, `List<Membership> findMemberships(UUID organizationId)`.

- [ ] **Step 1: Write the failing integration test `src/test/java/com/zarlania/api/organizations/service/OrganizationServiceTest.java`.**

```java
package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.exception.DuplicateMembershipException;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
import com.zarlania.api.organizations.exception.PersonalOrganizationMembershipException;
import com.zarlania.api.organizations.support.OrganizationTestSupport;
import com.zarlania.api.persistence.JpaConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, OrganizationService.class, OrganizationMapper.class})
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class OrganizationServiceTest {

  @Autowired private OrganizationService service;
  @Autowired private TestEntityManager entityManager;

  // Local binder over the shared seed helper (the SQL lives once in OrganizationTestSupport), so the
  // memberships.user_id FK is satisfied without importing the users domain.
  private UUID seedUser(String email) {
    return OrganizationTestSupport.seedUser(entityManager.getEntityManager(), email);
  }

  @Test
  void createPersonalOrganizationCreatesOrgWithItsSingleOwner() {
    UUID owner = seedUser("owner@example.com");

    Organization org = service.createPersonalOrganization(owner, "Owner's Space");

    assertThat(org.id()).isNotNull();
    assertThat(org.type()).isEqualTo(OrganizationType.PERSONAL);
    assertThat(service.findMemberships(org.id()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.userId()).isEqualTo(owner);
              assertThat(m.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void createPersonalOrganizationRejectsASecondForTheSameOwner() {
    UUID owner = seedUser("owner@example.com");
    service.createPersonalOrganization(owner, "First");

    assertThatThrownBy(() -> service.createPersonalOrganization(owner, "Second"))
        .isInstanceOf(PersonalOrganizationAlreadyExistsException.class);
  }

  @Test
  void differentOwnersMayEachHaveAPersonalOrganization() {
    UUID first = seedUser("first@example.com");
    UUID second = seedUser("second@example.com");

    service.createPersonalOrganization(first, "First Space");
    Organization secondOrg = service.createPersonalOrganization(second, "Second Space");

    assertThat(secondOrg.id()).isNotNull();
  }

  @Test
  void createGeneralOrganizationCreatesOrgWithCreatorAsOwner() {
    UUID creator = seedUser("creator@example.com");

    Organization org = service.createGeneralOrganization(creator, "Acme");

    assertThat(org.type()).isEqualTo(OrganizationType.GENERAL);
    assertThat(service.findMemberships(org.id()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.userId()).isEqualTo(creator);
              assertThat(m.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void addMemberAddsANonOwnerToAGeneralOrganization() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    Membership membership = service.addMember(org.id(), member);

    assertThat(membership.role()).isEqualTo(MembershipRole.MEMBER);
    assertThat(service.findMemberships(org.id())).hasSize(2);
  }

  @Test
  void addMemberIsRejectedForAPersonalOrganization() {
    UUID owner = seedUser("owner@example.com");
    UUID other = seedUser("other@example.com");
    Organization personal = service.createPersonalOrganization(owner, "Mine");

    assertThatThrownBy(() -> service.addMember(personal.id(), other))
        .isInstanceOf(PersonalOrganizationMembershipException.class);
  }

  @Test
  void addMemberIsRejectedForADuplicateMembership() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");
    service.addMember(org.id(), member);

    assertThatThrownBy(() -> service.addMember(org.id(), member))
        .isInstanceOf(DuplicateMembershipException.class);
  }

  @Test
  void addMemberIsRejectedForAnUnknownOrganization() {
    UUID member = seedUser("member@example.com");

    assertThatThrownBy(() -> service.addMember(UUID.randomUUID(), member))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void addOwnerAddsANewOwnerToAGeneralOrganization() {
    UUID creator = seedUser("creator@example.com");
    UUID coOwner = seedUser("coowner@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    Membership membership = service.addOwner(org.id(), coOwner);

    assertThat(membership.role()).isEqualTo(MembershipRole.OWNER);
    assertThat(service.findMemberships(org.id()))
        .filteredOn(m -> m.role() == MembershipRole.OWNER)
        .hasSize(2);
  }

  @Test
  void addOwnerPromotesAnExistingMemberWithoutCreatingASecondMembership() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");
    service.addMember(org.id(), member);

    Membership promoted = service.addOwner(org.id(), member);

    assertThat(promoted.role()).isEqualTo(MembershipRole.OWNER);
    // creator (owner) + the now-promoted member: still exactly two memberships, no duplicate row.
    List<Membership> all = service.findMemberships(org.id());
    assertThat(all).hasSize(2);
    assertThat(all).filteredOn(m -> m.userId().equals(member)).singleElement();
  }

  @Test
  void addOwnerIsRejectedForAPersonalOrganization() {
    UUID owner = seedUser("owner@example.com");
    UUID other = seedUser("other@example.com");
    Organization personal = service.createPersonalOrganization(owner, "Mine");

    assertThatThrownBy(() -> service.addOwner(personal.id(), other))
        .isInstanceOf(PersonalOrganizationMembershipException.class);
  }

  @Test
  void generalOrganizationMayHaveMultipleOwnersAndMembers() {
    UUID creator = seedUser("creator@example.com");
    UUID secondOwner = seedUser("owner2@example.com");
    UUID firstMember = seedUser("member1@example.com");
    UUID secondMember = seedUser("member2@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    service.addOwner(org.id(), secondOwner);
    service.addMember(org.id(), firstMember);
    service.addMember(org.id(), secondMember);

    List<Membership> all = service.findMemberships(org.id());
    assertThat(all).filteredOn(m -> m.role() == MembershipRole.OWNER).hasSize(2);
    assertThat(all).filteredOn(m -> m.role() == MembershipRole.MEMBER).hasSize(2);
  }

  @Test
  void findByIdReturnsTheCreatedOrganization() {
    UUID creator = seedUser("creator@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    assertThat(service.findById(org.id())).contains(org);
  }

  @Test
  void findByIdIsEmptyForAnUnknownOrganization() {
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }
}
```

- [ ] **Step 2: Run it to verify it fails.**

Run: `./mvnw -q test -Dtest=OrganizationServiceTest`
Expected: FAIL — compilation error (the four exceptions and `OrganizationService` do not exist).

- [ ] **Step 3: Create `OrganizationNotFoundException.java`.**

```java
package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when an operation targets an organization id that does not exist. */
@Getter
public class OrganizationNotFoundException extends RuntimeException {

  /** The id that did not resolve to an organization. */
  private final UUID organizationId;

  private OrganizationNotFoundException(UUID organizationId) {
    super("No organization exists with the given id");
    this.organizationId = organizationId;
  }

  /**
   * Creates the exception for a missing organization.
   *
   * @param organizationId the id that did not resolve
   * @return an exception describing the miss
   */
  public static OrganizationNotFoundException forId(UUID organizationId) {
    return new OrganizationNotFoundException(organizationId);
  }
}
```

- [ ] **Step 4: Create `PersonalOrganizationAlreadyExistsException.java`.**

```java
package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when creating a second personal organization for an owner who already has one. */
@Getter
public class PersonalOrganizationAlreadyExistsException extends RuntimeException {

  /** The owner who already has a personal organization. */
  private final UUID ownerUserId;

  private PersonalOrganizationAlreadyExistsException(UUID ownerUserId) {
    super("The user already owns a personal organization");
    this.ownerUserId = ownerUserId;
  }

  /**
   * Creates the exception for an owner who already has a personal organization.
   *
   * @param ownerUserId the owner's user id
   * @return an exception describing the conflict
   */
  public static PersonalOrganizationAlreadyExistsException forOwner(UUID ownerUserId) {
    return new PersonalOrganizationAlreadyExistsException(ownerUserId);
  }
}
```

- [ ] **Step 5: Create `PersonalOrganizationMembershipException.java`.**

```java
package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/**
 * Thrown when adding a member or owner to a {@code PERSONAL} organization, which admits no members
 * beyond its single owner.
 */
@Getter
public class PersonalOrganizationMembershipException extends RuntimeException {

  /** The personal organization that may not take additional members. */
  private final UUID organizationId;

  private PersonalOrganizationMembershipException(UUID organizationId) {
    super("A personal organization admits no members beyond its owner");
    this.organizationId = organizationId;
  }

  /**
   * Creates the exception for the offending personal organization.
   *
   * @param organizationId the personal organization's id
   * @return an exception describing the rejection
   */
  public static PersonalOrganizationMembershipException forOrganization(UUID organizationId) {
    return new PersonalOrganizationMembershipException(organizationId);
  }
}
```

- [ ] **Step 6: Create `DuplicateMembershipException.java`.**

```java
package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when adding a member who already has a membership in the target organization. */
@Getter
public class DuplicateMembershipException extends RuntimeException {

  /** The organization the user already belongs to. */
  private final UUID organizationId;

  /** The user who already has a membership. */
  private final UUID userId;

  private DuplicateMembershipException(UUID organizationId, UUID userId) {
    super("The user already has a membership in the organization");
    this.organizationId = organizationId;
    this.userId = userId;
  }

  /**
   * Creates the exception for a duplicate membership.
   *
   * @param organizationId the organization id
   * @param userId the user already in the organization
   * @return an exception describing the conflict
   */
  public static DuplicateMembershipException forMembership(UUID organizationId, UUID userId) {
    return new DuplicateMembershipException(organizationId, userId);
  }
}
```

- [ ] **Step 7: Create `OrganizationService.java`.**

```java
package com.zarlania.api.organizations.service;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.organizations.exception.DuplicateMembershipException;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
import com.zarlania.api.organizations.exception.PersonalOrganizationMembershipException;
import com.zarlania.api.organizations.repository.MembershipRepository;
import com.zarlania.api.organizations.repository.OrganizationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates organizations and manages their memberships, enforcing every ownership invariant. The
 * public surface of the {@code organizations} domain. References users only by opaque id — it never
 * loads the {@code users} domain (ADR-0011).
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizationRepository;
  private final MembershipRepository membershipRepository;
  private final OrganizationMapper organizationMapper;

  /**
   * Creates a user's personal organization and its single owner membership. Enforces the
   * uniqueness half of the 1:1 rule: rejects a second personal organization for an owner who
   * already has one.
   *
   * @param ownerUserId the owning user's id
   * @param name the organization's display name
   * @return the created organization
   * @throws IllegalArgumentException if {@code ownerUserId} is null or {@code name} is blank
   * @throws PersonalOrganizationAlreadyExistsException if the owner already has a personal organization
   */
  @Transactional
  public Organization createPersonalOrganization(UUID ownerUserId, String name) {
    requireNonNull(ownerUserId, "ownerUserId");
    requireNonBlank(name, "name");
    if (membershipRepository.existsByUserIdAndRoleAndOrganization_Type(
        ownerUserId, MembershipRole.OWNER, OrganizationType.PERSONAL)) {
      throw PersonalOrganizationAlreadyExistsException.forOwner(ownerUserId);
    }
    OrganizationEntity organization = saveOrganization(name, OrganizationType.PERSONAL);
    addMembership(organization, ownerUserId, MembershipRole.OWNER);
    return organizationMapper.toDto(organization);
  }

  /**
   * Creates a general (company) organization with the creator as its first owner.
   *
   * @param creatorUserId the creating user's id
   * @param name the organization's display name
   * @return the created organization
   * @throws IllegalArgumentException if {@code creatorUserId} is null or {@code name} is blank
   */
  @Transactional
  public Organization createGeneralOrganization(UUID creatorUserId, String name) {
    requireNonNull(creatorUserId, "creatorUserId");
    requireNonBlank(name, "name");
    OrganizationEntity organization = saveOrganization(name, OrganizationType.GENERAL);
    addMembership(organization, creatorUserId, MembershipRole.OWNER);
    return organizationMapper.toDto(organization);
  }

  /**
   * Adds a non-owner member to a general organization.
   *
   * @param organizationId the organization to add to
   * @param userId the user to add
   * @return the created membership
   * @throws IllegalArgumentException if either id is null
   * @throws OrganizationNotFoundException if no organization has that id
   * @throws PersonalOrganizationMembershipException if the organization is personal
   * @throws DuplicateMembershipException if the user already belongs to the organization
   */
  @Transactional
  public Membership addMember(UUID organizationId, UUID userId) {
    requireNonNull(organizationId, "organizationId");
    requireNonNull(userId, "userId");
    OrganizationEntity organization = requireGeneralOrganization(organizationId);
    if (membershipRepository.existsByOrganization_IdAndUserId(organizationId, userId)) {
      throw DuplicateMembershipException.forMembership(organizationId, userId);
    }
    return organizationMapper.toDto(addMembership(organization, userId, MembershipRole.MEMBER));
  }

  /**
   * Adds the user to a general organization as an owner, promoting an existing membership if one
   * is present (so no duplicate membership is created).
   *
   * @param organizationId the organization to add to
   * @param userId the user to make an owner
   * @return the owner membership
   * @throws IllegalArgumentException if either id is null
   * @throws OrganizationNotFoundException if no organization has that id
   * @throws PersonalOrganizationMembershipException if the organization is personal
   */
  @Transactional
  public Membership addOwner(UUID organizationId, UUID userId) {
    requireNonNull(organizationId, "organizationId");
    requireNonNull(userId, "userId");
    OrganizationEntity organization = requireGeneralOrganization(organizationId);
    MembershipEntity membership =
        membershipRepository
            .findByOrganization_IdAndUserId(organizationId, userId)
            .orElseGet(
                () -> {
                  MembershipEntity created = new MembershipEntity();
                  created.setOrganization(organization);
                  created.setUserId(userId);
                  return created;
                });
    membership.setRole(MembershipRole.OWNER);
    return organizationMapper.toDto(membershipRepository.save(membership));
  }

  /**
   * Finds an organization by id.
   *
   * @param id the organization id
   * @return the organization as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<Organization> findById(UUID id) {
    return organizationRepository.findById(id).map(organizationMapper::toDto);
  }

  /**
   * Lists an organization's memberships.
   *
   * @param organizationId the organization id
   * @return the memberships as DTOs (empty if the organization has none or does not exist)
   */
  @Transactional(readOnly = true)
  public List<Membership> findMemberships(UUID organizationId) {
    return membershipRepository.findByOrganization_Id(organizationId).stream()
        .map(organizationMapper::toDto)
        .toList();
  }

  private OrganizationEntity requireGeneralOrganization(UUID organizationId) {
    OrganizationEntity organization =
        organizationRepository
            .findById(organizationId)
            .orElseThrow(() -> OrganizationNotFoundException.forId(organizationId));
    if (organization.getType() == OrganizationType.PERSONAL) {
      throw PersonalOrganizationMembershipException.forOrganization(organizationId);
    }
    return organization;
  }

  private OrganizationEntity saveOrganization(String name, OrganizationType type) {
    OrganizationEntity organization = new OrganizationEntity();
    organization.setName(name);
    organization.setType(type);
    return organizationRepository.save(organization);
  }

  private MembershipEntity addMembership(
      OrganizationEntity organization, UUID userId, MembershipRole role) {
    MembershipEntity membership = new MembershipEntity();
    membership.setOrganization(organization);
    membership.setUserId(userId);
    membership.setRole(role);
    return membershipRepository.save(membership);
  }

  private static void requireNonNull(UUID value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
```

- [ ] **Step 8: Format, then run the integration test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=OrganizationServiceTest`
Expected: PASS (all fifteen tests).

- [ ] **Step 9: Write the failing pure-unit test `src/test/java/com/zarlania/api/organizations/service/OrganizationServiceUnitTest.java`.**

This covers the input-validation guards, which throw before touching any repository — so it needs no database. Mocks are present only as constructor collaborators; the guards reject before calling them.

```java
package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zarlania.api.organizations.repository.MembershipRepository;
import com.zarlania.api.organizations.repository.OrganizationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceUnitTest {

  @Mock private OrganizationRepository organizationRepository;
  @Mock private MembershipRepository membershipRepository;

  private OrganizationService service() {
    return new OrganizationService(
        organizationRepository, membershipRepository, new OrganizationMapper());
  }

  @Test
  void createPersonalOrganizationRejectsNullOwner() {
    assertThatThrownBy(() -> service().createPersonalOrganization(null, "Name"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void createPersonalOrganizationRejectsBlankName() {
    assertThatThrownBy(() -> service().createPersonalOrganization(UUID.randomUUID(), "  "))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void createPersonalOrganizationRejectsNullName() {
    assertThatThrownBy(() -> service().createPersonalOrganization(UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void createGeneralOrganizationRejectsNullCreator() {
    assertThatThrownBy(() -> service().createGeneralOrganization(null, "Name"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void createGeneralOrganizationRejectsBlankName() {
    assertThatThrownBy(() -> service().createGeneralOrganization(UUID.randomUUID(), "  "))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void addMemberRejectsNullOrganizationId() {
    assertThatThrownBy(() -> service().addMember(null, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void addMemberRejectsNullUserId() {
    assertThatThrownBy(() -> service().addMember(UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void addOwnerRejectsNullOrganizationId() {
    assertThatThrownBy(() -> service().addOwner(null, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }

  @Test
  void addOwnerRejectsNullUserId() {
    assertThatThrownBy(() -> service().addOwner(UUID.randomUUID(), null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(organizationRepository, membershipRepository);
  }
}
```

- [ ] **Step 10: Run the unit test to verify it passes.**

Run: `./mvnw -q spotless:apply && ./mvnw -q test -Dtest=OrganizationServiceUnitTest`
Expected: PASS (all nine tests).

- [ ] **Step 11: Commit.**

```bash
git add src/main/java/com/zarlania/api/organizations/exception/ \
  src/main/java/com/zarlania/api/organizations/service/OrganizationService.java \
  src/test/java/com/zarlania/api/organizations/service/OrganizationServiceTest.java \
  src/test/java/com/zarlania/api/organizations/service/OrganizationServiceUnitTest.java
git commit -m "feat: add OrganizationService with all ownership invariants

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Finalize (full verify, version bump, PR)

- [ ] **Step 1: Run the full build with every gate.**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS — Spotless, Checkstyle, SpotBugs/FindSecBugs, all tests, and JaCoCo ≥ 80% line/branch all pass. Fix any reported issue at the root cause (never suppress a gate); re-run until green. If branch coverage falls short, the gap is almost certainly an unexercised rejection branch in `OrganizationService` — add the missing case to the integration or unit test rather than lowering the threshold.

- [ ] **Step 2: Bump the version for the minor release.**

```bash
./scripts/bump-version bump minor
```

Confirm `pom.xml` `<version>` is now `0.3.0`.

- [ ] **Step 3: Update the spec's Phase status table.**

In `docs/superpowers/specs/2026-06-17-users-and-organizations-design.md`, change the Phase 2 row from `Not started` to `✅ Done (v0.3.0)` and update the top-of-file Status line to note Phase 2 implemented. (Commit on this branch so it rides the PR; per CLAUDE.md the spec is implementation-time guidance, and updating its own status table during the change that implements it is the documented flow.)

- [ ] **Step 4: Commit the bump + status update and push.**

```bash
git add pom.xml docs/superpowers/specs/2026-06-17-users-and-organizations-design.md
git commit -m "chore: bump version to 0.3.0 for phase 2 release

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push -u origin feat/<issue#>-phase-2-organizations
```

- [ ] **Step 5: Open the PR (title references the issue; `release:minor` label).**

```bash
gh pr create --label "release:minor" \
  --title "Phase 2: organizations domain (#<issue#>)" \
  --body "$(cat <<'EOF'
Implements Phase 2 of the users-and-organizations spec.

- V2 migration: organizations + memberships tables, memberships.user_id -> users.id FK,
  (organization_id, user_id) unique constraint, type/role CHECK constraints
- organizations domain: OrganizationEntity + MembershipEntity (same-domain @ManyToOne, opaque
  userId column — no users-domain import), repositories, Organization/Membership DTOs, mapper
- OrganizationService: personal/general creation, addMember, addOwner (promote-on-conflict),
  reads, and every ownership invariant (exactly one personal org per user; personal org admits
  no extra members; general orgs allow multiple owners/members; no duplicate memberships)
- No new ADRs — applies ADR-0010/0011/0012. Version bumped 0.2.0 -> 0.3.0 (release:minor)

Closes #<issue#>

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review (completed during planning)

- **Spec coverage (Phase 2 section):** V2 migration with `memberships.user_id` → `users.id` FK (Task 1) ✓; `Organization` + `Membership` entities extending `Auditable`, enums, repositories (Task 2) ✓; `OrganizationDto`/`MembershipDto` as canonical-named records + mapper (Task 3) ✓; `OrganizationService` with `createPersonalOrganization`/`createGeneralOrganization`/`addMember`/`addOwner` + reads (Task 4) ✓; every invariant — exactly one personal org per user, personal org admits no extra members, general org allows multiple owners/members, every org has ≥1 owner (holds by construction: every org is created with an OWNER and no removal/demotion operation exists this pass), email uniqueness (owned by Phase 1) — proven by tests (Task 4) ✓; integration tests on real migrated H2 schema + Mockito unit tests (Tasks 2–4) ✓; AssertJ throughout ✓; no controllers, no cross-domain orchestration, no user-existence validation (the service stores `userId` opaquely; the DB FK is the only integrity check) ✓; no new ADRs ✓.
- **Domain-boundary check (ADR-0011):** `src/main` `organizations` code never imports `com.zarlania.api.users.*` — `userId` is a plain `UUID`; the only user link is the migration FK. Tests seed `users` rows via native SQL, not via the users domain, so even tests carry no compile-time coupling.
- **Type consistency:** `OrganizationType`/`MembershipRole` constants, `OrganizationEntity`/`MembershipEntity` accessors, the four `MembershipRepository` derived-query signatures, `OrganizationMapper.toDto` overloads, the exception factories (`forId`/`forOwner`/`forOrganization`/`forMembership`), and the six `OrganizationService` method signatures are used identically across Tasks 2–4 and in every test. Spring Boot 4.1 test imports (`org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`, `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`) match the merged Phase 1 tests exactly.
- **Branch coverage:** every rejection branch has a test — second personal org, personal-org member/owner rejection, duplicate membership, unknown organization, and each null/blank guard (`requireNonNull` via null-id tests, `requireNonBlank` via both null-name and blank-name tests). `addOwner`'s `orElseGet` (create) and present (promote) branches are both covered.
- **No placeholders:** every code/command step is complete; the only `<issue#>` tokens are values produced during execution.
