package com.zarlania.api.organizations.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.entities.Membership;
import com.zarlania.api.organizations.entities.Organization;
import com.zarlania.api.organizations.entities.OrganizationType;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries this repository declares itself, against real Postgres.
 *
 * <p>{@code deleteByUserId} carries the weight here. {@code organization_memberships.user_id} is a
 * NOT NULL foreign key to {@code users}, so a single row this fails to clear makes the account
 * permanently unpurgeable — it would be retried and fail identically on every future sweep, holding
 * its unique address and username hostage forever.
 *
 * <p>Transactional at the class level, which does two things at once. Derived deletes and
 * {@code @Modifying} queries need a transaction to run in at all — in production the calling
 * service supplies one, and there is no service here. And Spring rolls the transaction back after
 * each test, which is what keeps these isolated from every other class sharing the one container.
 */
@Transactional
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class MembershipRepositoryIntegrationTest extends IntegrationTestBase {

  private final MembershipRepository memberships;
  private final OrganizationRepository organizations;
  private final OrganizationService organizationService;
  private final TestAccounts accounts;

  @Test
  void findByUserIdReturnsEveryOrganizationTheAccountBelongsTo() {
    UserDto user = accounts.user("membership-repo-find");
    organizationService.createPersonalOrganization(user.id(), "membership-repo-find");
    joinSharedOrganization(user.id(), "Shared For Find");

    assertThat(memberships.findByUserId(user.id())).hasSize(2);
  }

  @Test
  void findByUserIdIsEmptyForAnAccountThatBelongsToNothing() {
    UserDto user = accounts.user("membership-repo-none");

    assertThat(memberships.findByUserId(user.id())).isEmpty();
  }

  @Test
  void existsByUserIdAndOrganizationIdIgnoresOwnershipAndOtherAccounts() {
    UserDto member = accounts.user("membership-repo-member");
    UserDto stranger = accounts.user("membership-repo-stranger");
    OrganizationDto own =
        organizationService.createPersonalOrganization(member.id(), "membership-repo-member");

    assertThat(memberships.existsByUserIdAndOrganizationId(member.id(), own.id())).isTrue();
    assertThat(memberships.existsByUserIdAndOrganizationId(stranger.id(), own.id())).isFalse();
  }

  // Every row, whether the account owns the organization or merely belongs to it — and without
  // touching anyone else's membership of the same organization.
  @Test
  void deleteByUserIdClearsOwnedAndNonOwnedMembershipsAndLeavesOtherMembersAlone() {
    UserDto leaving = accounts.user("membership-repo-leaving");
    UserDto staying = accounts.user("membership-repo-staying");
    organizationService.createPersonalOrganization(leaving.id(), "membership-repo-leaving");
    UUID sharedId = joinSharedOrganization(leaving.id(), "Shared For Delete");
    memberships.saveAndFlush(
        new Membership(organizations.findById(sharedId).orElseThrow(), staying.id(), false));

    memberships.deleteByUserId(leaving.id());

    assertThat(memberships.findByUserId(leaving.id())).isEmpty();
    assertThat(memberships.findByUserId(staying.id())).hasSize(1);
    assertThat(organizations.findById(sharedId)).isPresent();
  }

  private UUID joinSharedOrganization(UUID userId, String name) {
    Organization shared =
        organizations.saveAndFlush(new Organization(name, OrganizationType.GENERAL));
    memberships.saveAndFlush(new Membership(shared, userId, false));
    return shared.getId();
  }
}
