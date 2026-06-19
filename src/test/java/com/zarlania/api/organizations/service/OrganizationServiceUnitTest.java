package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.organizations.exception.DuplicateMembershipException;
import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.repository.MembershipRepository;
import com.zarlania.api.organizations.repository.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

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

  @Test
  void createTranslatesNameUniquenessRaceIntoDomainException() {
    // Simulate the concurrent-duplicate race: the INSERT trips the organization-name unique
    // constraint. The service must surface the domain exception, not a raw persistence error.
    when(organizationRepository.saveAndFlush(any(OrganizationEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_ORGANIZATIONS_NAME\"]"));

    assertThatThrownBy(() -> service().createGeneralOrganization(UUID.randomUUID(), "Acme"))
        .isInstanceOf(OrganizationNameAlreadyExistsException.class);
  }

  @Test
  void createRethrowsIntegrityViolationUnrelatedToNameUniqueness() {
    // A different integrity failure must NOT be reported as a duplicate name — it propagates.
    when(organizationRepository.saveAndFlush(any(OrganizationEntity.class)))
        .thenThrow(new DataIntegrityViolationException("Value too long for column \"NAME\""));

    assertThatThrownBy(() -> service().createGeneralOrganization(UUID.randomUUID(), "Acme"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(OrganizationNameAlreadyExistsException.class);
  }

  @Test
  void addMemberTranslatesMembershipUniquenessRaceIntoDomainException() {
    UUID organizationId = UUID.randomUUID();
    OrganizationEntity organization = new OrganizationEntity();
    ReflectionTestUtils.setField(organization, "id", organizationId);
    organization.setType(OrganizationType.GENERAL);
    when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));

    UUID userId = UUID.randomUUID();
    when(membershipRepository.existsByOrganizationIdAndUserId(organizationId, userId))
        .thenReturn(false);
    // The pre-check passes, but the persisted INSERT trips the (organization_id, user_id)
    // constraint.
    when(membershipRepository.saveAndFlush(any(MembershipEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_MEMBERSHIPS_ORG_USER\"]"));

    assertThatThrownBy(() -> service().addMember(organizationId, userId))
        .isInstanceOf(DuplicateMembershipException.class);
  }
}
