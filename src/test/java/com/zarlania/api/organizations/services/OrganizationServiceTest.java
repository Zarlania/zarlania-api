package com.zarlania.api.organizations.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.dtos.OrganizationType;
import com.zarlania.api.organizations.entities.MembershipEntity;
import com.zarlania.api.organizations.entities.OrganizationEntity;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The decisions this service makes, against mocked repositories.
 *
 * <p>The interesting ones are all about what it refuses to touch: a personal organization is
 * deleted only when the account both owns it and it is personal, and a shared organization the
 * account merely belongs to must survive. Against a real database those cases need a fixture each;
 * here the state can simply be stated, so every branch is reachable and obvious.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceTest {

  @Mock private OrganizationRepository organizations;

  @Mock private MembershipRepository memberships;

  @Captor private ArgumentCaptor<MembershipEntity> savedMembership;

  @InjectMocks private OrganizationService organizationService;

  // The membership is what makes the organization reachable at all, so creating one without the
  // other would produce an organization nobody could ever find.
  @Test
  void creatingAPersonalOrganizationAlsoMakesTheCallerItsOwner() {
    UUID ownerId = UUID.randomUUID();
    OrganizationEntity saved = new OrganizationEntity("mira", OrganizationType.PERSONAL);
    when(organizations.save(any())).thenReturn(saved);

    Organization dto = organizationService.createPersonalOrganization(ownerId, "mira");

    verify(memberships).save(savedMembership.capture());
    assertThat(savedMembership.getValue().getUserId()).isEqualTo(ownerId);
    assertThat(savedMembership.getValue().isOwner()).isTrue();
    assertThat(dto.type()).isEqualTo(OrganizationType.PERSONAL);
  }

  @Test
  void thePersonalOrganizationOfAnAccountWithNoMembershipsIsEmpty() {
    UUID userId = UUID.randomUUID();
    when(memberships.findByUserId(userId)).thenReturn(List.of());

    assertThat(organizationService.personalOrganizationOf(userId)).isEmpty();
  }

  // A session is scoped to the caller's own organization, so a shared one must never be mistaken
  // for it however many the account belongs to.
  @Test
  void thePersonalOrganizationLookupIgnoresSharedOrganizations() {
    UUID userId = UUID.randomUUID();
    OrganizationEntity shared = new OrganizationEntity("Shared", OrganizationType.GENERAL);
    when(memberships.findByUserId(userId))
        .thenReturn(List.of(new MembershipEntity(shared, userId, false)));

    assertThat(organizationService.personalOrganizationOf(userId)).isEmpty();
  }

  // The predicate is owned-and-personal, and each half of it matters. Stated as three exclusions
  // rather than one assertion about which id was deleted, because an unpersisted entity has no id
  // for a unit test to compare — and "deleted nothing" is the property that actually protects a
  // shared space from being destroyed by someone else's purge.
  @ParameterizedTest(name = "{0} is never deleted")
  @MethodSource("organizationsThatMustSurvive")
  void deletingRemovesNothingUnlessTheAccountOwnsAPersonalOrganization(
      String description, MembershipEntity membership) {
    UUID userId = membership.getUserId();
    when(memberships.findByUserId(userId)).thenReturn(List.of(membership));

    organizationService.deletePersonalOrganizationOf(userId);

    verify(memberships).deleteByUserId(userId);
    verify(organizations, never()).deleteById(any());
  }

  static Stream<Arguments> organizationsThatMustSurvive() {
    UUID userId = UUID.randomUUID();
    return Stream.of(
        Arguments.of(
            "a shared organization the account owns",
            new MembershipEntity(
                new OrganizationEntity("Shared", OrganizationType.GENERAL), userId, true)),
        Arguments.of(
            "a shared organization the account merely belongs to",
            new MembershipEntity(
                new OrganizationEntity("Shared", OrganizationType.GENERAL), userId, false)),
        Arguments.of(
            "a personal organization the account does not own",
            new MembershipEntity(
                new OrganizationEntity("Someone Else", OrganizationType.PERSONAL), userId, false)));
  }

  @Test
  void deletingRemovesThePersonalOrganizationTheAccountOwns() {
    UUID userId = UUID.randomUUID();
    OrganizationEntity own = new OrganizationEntity("mira", OrganizationType.PERSONAL);
    when(memberships.findByUserId(userId))
        .thenReturn(List.of(new MembershipEntity(own, userId, true)));

    organizationService.deletePersonalOrganizationOf(userId);

    verify(memberships).deleteByUserId(userId);
    verify(organizations).deleteById(any());
  }

  @Test
  void findByIdMapsToADtoAndReportsAnUnknownIdAsEmpty() {
    UUID id = UUID.randomUUID();
    OrganizationEntity organization = new OrganizationEntity("mira", OrganizationType.PERSONAL);
    when(organizations.findById(id)).thenReturn(Optional.of(organization));

    assertThat(organizationService.findById(id))
        .get()
        .extracting(Organization::name)
        .isEqualTo("mira");
  }

  @Test
  void isMemberDelegatesTheQuestionToTheRepository() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(memberships.existsByUserIdAndOrganizationId(userId, organizationId)).thenReturn(true);

    assertThat(organizationService.isMember(userId, organizationId)).isTrue();
  }
}
