package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.services.AuthTokenService.MintedSession;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.errors.ApiException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Login and refresh composed over the real domains they orchestrate.
 *
 * <p>The unit test stubs each collaborator, so it can prove which one is called and in what order
 * but not that the pieces fit: that the token minted carries the organization the account actually
 * owns, that a refresh cookie issued by login is redeemable by refresh, and that a real Argon2 hash
 * in Postgres verifies. Those only appear when the collaborators are real.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class AuthTokenServiceIntegrationTest extends IntegrationTestBase {

  private static final String ORG_CLAIM = "org";

  private final AuthTokenService authTokenService;
  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final TestAccounts accounts;

  // Each case seeds its own account: the database is shared for the run, so one slug cannot be
  // registered three times.
  @ParameterizedTest(name = "logging in with {1}")
  @CsvSource({
    "tokensvcname, tokensvcname",
    "tokensvcmail, tokensvcmail@example.com",
    "tokensvccase, TOKENSVCCASE@EXAMPLE.COM"
  })
  void loginAcceptsEitherIdentifierAndScopesTheTokenToThePersonalOrganization(
      String slug, String identifier) throws Exception {
    SeededSession holder = seedVerifiedAccount(slug);

    MintedSession session = authTokenService.login(identifier, TestAccounts.PASSWORD);

    String orgClaim =
        SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getStringClaim(ORG_CLAIM);
    assertThat(orgClaim).isEqualTo(holder.organizationId().toString());
    assertThat(SignedJWT.parse(session.accessToken()).getJWTClaimsSet().getSubject())
        .isEqualTo(holder.userId().toString());
  }

  // The cookie login hands out has to be the one refresh accepts. Nothing below this level can show
  // that, because each half stubs the other.
  @Test
  void aRefreshTokenIssuedByLoginIsRedeemableByRefresh() {
    seedVerifiedAccount("tokensvc-refresh");

    MintedSession login = authTokenService.login("tokensvc-refresh", TestAccounts.PASSWORD);
    MintedSession refreshed = authTokenService.refresh(login.refresh().raw());

    assertThat(refreshed.accessToken()).isNotEqualTo(login.accessToken());
    assertThat(refreshed.refresh().raw()).isNotEqualTo(login.refresh().raw());
  }

  @Test
  void replayingARedeemedRefreshTokenIsRefusedAndKillsTheSuccessorToo() {
    seedVerifiedAccount("tokensvc-replay");
    MintedSession login = authTokenService.login("tokensvc-replay", TestAccounts.PASSWORD);
    MintedSession refreshed = authTokenService.refresh(login.refresh().raw());

    assertThatThrownBy(() -> authTokenService.refresh(login.refresh().raw()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> authTokenService.refresh(refreshed.refresh().raw()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void logoutEndsTheSessionSoTheCookieIsNoLongerRedeemable() {
    seedVerifiedAccount("tokensvc-logout");
    MintedSession login = authTokenService.login("tokensvc-logout", TestAccounts.PASSWORD);

    authTokenService.logout(login.refresh().raw());

    assertThatThrownBy(() -> authTokenService.refresh(login.refresh().raw()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aWrongPasswordAgainstARealArgonHashIsRefused() {
    seedVerifiedAccount("tokensvc-wrongpw");

    assertThatThrownBy(() -> authTokenService.login("tokensvc-wrongpw", "not-the-password"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }

  // A right password on an unverified account is a different refusal from a wrong one, because the
  // caller has proved they own the account and needs to be told what to do about it.
  @Test
  void aRightPasswordOnAnUnverifiedAccountIsRefusedAsUnverified() {
    UserDto user = accounts.user("tokensvc-unverified");
    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);
    organizationService.createPersonalOrganization(user.id(), "tokensvc-unverified");

    assertThatThrownBy(() -> authTokenService.login("tokensvc-unverified", TestAccounts.PASSWORD))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).getErrorCode())
        .isEqualTo(ErrorCode.EMAIL_UNVERIFIED);
  }

  /** The ids a seeded account's minted token is expected to carry. */
  private record SeededSession(UUID userId, UUID organizationId) {}

  private SeededSession seedVerifiedAccount(String slug) {
    UserDto user = accounts.user(slug);
    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);
    OrganizationDto organization = organizationService.createPersonalOrganization(user.id(), slug);
    userService.markEmailVerified(user.id());
    return new SeededSession(user.id(), organization.id());
  }
}
