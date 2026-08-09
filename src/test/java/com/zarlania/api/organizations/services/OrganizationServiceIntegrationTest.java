package com.zarlania.api.organizations.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.dtos.OrganizationType;
import com.zarlania.api.organizations.entities.MembershipEntity;
import com.zarlania.api.organizations.entities.OrganizationEntity;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.users.entities.UserEntity;
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
    UserEntity owner = users.saveAndFlush(new UserEntity("org-owner@example.com", "orgowner"));

    Organization created =
        organizationService.createPersonalOrganization(owner.getId(), "Org Owner's Space");

    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("Org Owner's Space");
    assertThat(created.type()).isEqualTo(OrganizationType.PERSONAL);
  }

  @Test
  void createPersonalOrganizationPersistsAnOwnerMembership() {
    UserEntity owner =
        users.saveAndFlush(new UserEntity("membership-owner@example.com", "membershipowner"));

    Organization created =
        organizationService.createPersonalOrganization(owner.getId(), "Membership Owner's Space");

    MembershipEntity membership =
        memberships.findByUserId(owner.getId()).stream()
            .filter(m -> m.getOrganization().getId().equals(created.id()))
            .findFirst()
            .orElseThrow();
    assertThat(membership.getUserId()).isEqualTo(owner.getId());
    assertThat(membership.isOwner()).isTrue();
  }

  @Test
  void personalOrganizationOfFindsTheOwnersPersonalOrganization() {
    UserEntity owner = users.saveAndFlush(new UserEntity("finder@example.com", "finder"));
    Organization created =
        organizationService.createPersonalOrganization(owner.getId(), "Finder's Space");

    Optional<Organization> found = organizationService.personalOrganizationOf(owner.getId());

    assertThat(found).contains(created);
  }

  @Test
  void personalOrganizationOfIsEmptyForUserWithNoOrganization() {
    Optional<Organization> found = organizationService.personalOrganizationOf(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  void personalOrganizationOfIgnoresGeneralOrganizationMembership() {
    UserEntity member =
        users.saveAndFlush(new UserEntity("general-member@example.com", "generalmember"));
    OrganizationEntity generalOrg =
        organizations.saveAndFlush(
            new OrganizationEntity("A General Space", OrganizationType.GENERAL));
    memberships.saveAndFlush(new MembershipEntity(generalOrg, member.getId(), false));

    Optional<Organization> found = organizationService.personalOrganizationOf(member.getId());

    assertThat(found).isEmpty();
  }

  // The type check alone is not enough, because PERSONAL says what an organization is for, not
  // whose it is. A membership row pointing at somebody else's personal organization would satisfy
  // it and hand this account a session scoped into another person's space — so ownership is the
  // half that actually answers "which one is mine".
  @Test
  void personalOrganizationOfIgnoresAPersonalOrganizationTheAccountDoesNotOwn() {
    UserEntity owner =
        users.saveAndFlush(new UserEntity("personal-owner@example.com", "personalowner"));
    UserEntity guest =
        users.saveAndFlush(new UserEntity("personal-guest@example.com", "personalguest"));
    Organization ownersOwn =
        organizationService.createPersonalOrganization(owner.getId(), "Personal Owner's Space");
    OrganizationEntity ownersEntity = organizations.findById(ownersOwn.id()).orElseThrow();
    memberships.saveAndFlush(new MembershipEntity(ownersEntity, guest.getId(), false));

    Optional<Organization> found = organizationService.personalOrganizationOf(guest.getId());

    assertThat(found).isEmpty();
    // And the owner still finds it, so the tightened predicate excluded only the guest.
    assertThat(organizationService.personalOrganizationOf(owner.getId())).contains(ownersOwn);
  }

  @Test
  void findByIdFindsAPreviouslyCreatedOrganization() {
    UserEntity owner = users.saveAndFlush(new UserEntity("lookup@example.com", "lookup"));
    Organization created =
        organizationService.createPersonalOrganization(owner.getId(), "Lookup's Space");

    Optional<Organization> found = organizationService.findById(created.id());

    assertThat(found).contains(created);
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    Optional<Organization> found = organizationService.findById(UUID.randomUUID());

    assertThat(found).isEmpty();
  }

  @Test
  void isMemberIsTrueForTheOwnerAndFalseForARandomUser() {
    UserEntity owner = users.saveAndFlush(new UserEntity("member@example.com", "member"));
    Organization created =
        organizationService.createPersonalOrganization(owner.getId(), "Member's Space");

    assertThat(organizationService.isMember(owner.getId(), created.id())).isTrue();
    assertThat(organizationService.isMember(UUID.randomUUID(), created.id())).isFalse();
  }
}
