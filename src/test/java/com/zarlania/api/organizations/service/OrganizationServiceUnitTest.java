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
