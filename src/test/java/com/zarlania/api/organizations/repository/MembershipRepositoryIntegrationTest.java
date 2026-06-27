package com.zarlania.api.organizations.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.organizations.support.OrganizationTestSupport;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// The H2 pin and between-test cleanup are inherited from AbstractIntegrationTest.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class MembershipRepositoryIntegrationTest extends AbstractIntegrationTest {

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

    List<MembershipEntity> found = memberships.findByOrganizationId(org.getId());

    assertThat(found).hasSize(2);
  }

  @Test
  void existsByOrganizationAndUserReflectsState() {
    UUID userId = seedUser("owner3@example.com");
    OrganizationEntity org = saveOrganization("Acme", OrganizationType.GENERAL);

    assertThat(memberships.existsByOrganizationIdAndUserId(org.getId(), userId)).isFalse();
    memberships.save(newMembership(org, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThat(memberships.existsByOrganizationIdAndUserId(org.getId(), userId)).isTrue();
  }

  @Test
  void existsByUserRoleAndOrganizationTypeDetectsPersonalOwner() {
    UUID userId = seedUser("owner4@example.com");
    OrganizationEntity personal = saveOrganization("Mine", OrganizationType.PERSONAL);
    memberships.save(newMembership(personal, userId, MembershipRole.OWNER));
    entityManager.flush();

    assertThat(
            memberships.existsByUserIdAndRoleAndOrganizationType(
                userId, MembershipRole.OWNER, OrganizationType.PERSONAL))
        .isTrue();
    assertThat(
            memberships.existsByUserIdAndRoleAndOrganizationType(
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
