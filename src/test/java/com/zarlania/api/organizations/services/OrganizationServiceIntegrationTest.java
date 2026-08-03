package com.zarlania.api.organizations.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.entities.Membership;
import com.zarlania.api.organizations.entities.Organization;
import com.zarlania.api.organizations.entities.OrganizationType;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RequiredArgsConstructor(onConstructor_ = @Autowired)
class OrganizationServiceIntegrationTest extends IntegrationTestBase {

  private final OrganizationService organizationService;
  private final UserRepository users;
  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;

  @Test
  void createPersonalOrganizationPersistsOrganizationOfTypePersonal() {
    User owner = users.saveAndFlush(new User("org-owner@example.com", "orgowner"));

    OrganizationDto created =
        organizationService.createPersonalOrganization(owner.getId(), "Org Owner's Space");

    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("Org Owner's Space");
    assertThat(created.type()).isEqualTo(OrganizationType.PERSONAL);
  }

  @Test
  void createPersonalOrganizationPersistsAnOwnerMembership() {
    User owner = users.saveAndFlush(new User("membership-owner@example.com", "membershipowner"));

    OrganizationDto created =
        organizationService.createPersonalOrganization(owner.getId(), "Membership Owner's Space");

    Membership membership =
        memberships.findByUserId(owner.getId()).stream()
            .filter(m -> m.getOrganization().getId().equals(created.id()))
            .findFirst()
            .orElseThrow();
    assertThat(membership.getUserId()).isEqualTo(owner.getId());
    assertThat(membership.isOwner()).isTrue();
  }

  @Test
  void personalOrganizationOfFindsTheOwnersPersonalOrganization() {
    User owner = users.saveAndFlush(new User("finder@example.com", "finder"));
    OrganizationDto created =
        organizationService.createPersonalOrganization(owner.getId(), "Finder's Space");

    Optional<OrganizationDto> found = organizationService.personalOrganizationOf(owner.getId());

    assertThat(found).contains(created);
  }

  @Test
  void personalOrganizationOfIsEmptyForUserWithNoOrganization() {
    Optional<OrganizationDto> found = organizationService.personalOrganizationOf(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  void personalOrganizationOfIgnoresGeneralOrganizationMembership() {
    User member = users.saveAndFlush(new User("general-member@example.com", "generalmember"));
    Organization generalOrg =
        organizations.saveAndFlush(new Organization("A General Space", OrganizationType.GENERAL));
    memberships.saveAndFlush(new Membership(generalOrg, member.getId(), false));

    Optional<OrganizationDto> found = organizationService.personalOrganizationOf(member.getId());

    assertThat(found).isEmpty();
  }

  @Test
  void findByIdFindsAPreviouslyCreatedOrganization() {
    User owner = users.saveAndFlush(new User("lookup@example.com", "lookup"));
    OrganizationDto created =
        organizationService.createPersonalOrganization(owner.getId(), "Lookup's Space");

    Optional<OrganizationDto> found = organizationService.findById(created.id());

    assertThat(found).contains(created);
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    Optional<OrganizationDto> found = organizationService.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  void isMemberIsTrueForTheOwnerAndFalseForARandomUser() {
    User owner = users.saveAndFlush(new User("member@example.com", "member"));
    OrganizationDto created =
        organizationService.createPersonalOrganization(owner.getId(), "Member's Space");

    assertThat(organizationService.isMember(owner.getId(), created.id())).isTrue();
    assertThat(organizationService.isMember(UUID.randomUUID(), created.id())).isFalse();
  }
}
