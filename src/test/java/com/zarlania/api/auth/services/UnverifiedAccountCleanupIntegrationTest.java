package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.entities.Membership;
import com.zarlania.api.organizations.entities.Organization;
import com.zarlania.api.organizations.entities.OrganizationType;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.testsupport.AccountAssertions;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.testsupport.TestAccounts.SeededAccount;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Which accounts the sweep purges, and how completely.
 *
 * <p>Only the selection and the completeness of a purge are here. What happens when a purge fails
 * partway, or when an account verifies itself between being listed and being purged, is about
 * transaction boundaries and lives in {@link UnverifiedAccountCleanupTransactionTest}.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class UnverifiedAccountCleanupIntegrationTest extends IntegrationTestBase {

  // Comfortably past unverified-account-max-age (P7D in application.yml) without depending on the
  // exact configured duration, so this test still makes sense if that value ever changes.
  private static final Duration EXPIRED_AGE = Duration.ofDays(8);

  private final UnverifiedAccountCleanup cleanup;
  private final TestAccounts accounts;
  private final AccountAssertions accountAssertions;
  private final UserRepository users;
  private final CredentialsService credentialsService;
  private final PasswordCredentialRepository passwordCredentials;
  private final MembershipRepository memberships;
  private final OrganizationRepository organizations;
  private final EmailVerificationService emailVerificationService;
  private final EmailVerificationTokenRepository verificationTokens;

  // All three states in one sweep rather than three tests, because the property under test is that
  // the sweep discriminates: purging the expired account is only correct if it leaves the other two
  // alone in the same run.
  @Test
  void purgesOnlyTheAccountsThatAreBothExpiredAndStillUnverified() {
    SeededAccount expired = accounts.fullAccount("expired-unverified", false);
    accounts.backdateCreatedAt(expired.userId(), EXPIRED_AGE);
    SeededAccount fresh = accounts.fullAccount("fresh-unverified", false);
    SeededAccount verified = accounts.fullAccount("long-verified", true);
    accounts.backdateCreatedAt(verified.userId(), EXPIRED_AGE);

    cleanup.purgeExpiredUnverifiedAccounts();

    accountAssertions.assertFullyGone(expired);
    accountAssertions.assertFullyIntact(fresh);
    accountAssertions.assertFullyIntact(verified);
  }

  // Guards OrganizationService.deletePersonalOrganizationOf: a membership row that is not the
  // account's own owned personal organization — here a non-owning row in someone else's GENERAL
  // organization — must still be cleared. organization_memberships.user_id is a NOT NULL foreign
  // key to users, so a row left behind makes the final user delete fail forever: the account would
  // be retried and fail identically on every future sweep, permanently holding its citext-unique
  // address and username hostage. The shared organization itself must survive untouched, since
  // deleting it would destroy a space other members still use.
  @Test
  void purgesAnAccountThatBelongsToButDoesNotOwnAGeneralOrganization() {
    UserDto user = accounts.user("non-owning-member");
    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);
    String rawVerificationToken = emailVerificationService.issue(user.id());
    Organization sharedOrg =
        organizations.saveAndFlush(
            new Organization("Someone Else's Space", OrganizationType.GENERAL));
    memberships.saveAndFlush(new Membership(sharedOrg, user.id(), false));
    accounts.backdateCreatedAt(user.id(), EXPIRED_AGE);

    cleanup.purgeExpiredUnverifiedAccounts();

    assertThat(users.findById(user.id())).isEmpty();
    assertThat(passwordCredentials.findByUserId(user.id())).isEmpty();
    assertThat(verificationTokens.findByTokenHash(TokenHasher.sha256Hex(rawVerificationToken)))
        .isEmpty();
    assertThat(memberships.findByUserId(user.id())).isEmpty();
    assertThat(organizations.findById(sharedOrg.getId())).isPresent();
  }
}
