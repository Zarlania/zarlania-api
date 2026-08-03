package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.entities.EmailVerificationToken;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Both halves matter, and the second one more: a sweep that deletes every dead row is worthless if
 * it also deletes a live session's refresh token or an outstanding verification link.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ExpiredTokenCleanupIntegrationTest extends IntegrationTestBase {

  private static final Duration PAST = Duration.ofDays(1);
  private static final Duration FUTURE = Duration.ofDays(30);

  private final ExpiredTokenCleanup cleanup;
  private final RefreshTokenRepository refreshTokens;
  private final EmailVerificationTokenRepository verificationTokens;
  private final UserService userService;
  private final OrganizationService organizationService;
  private final Clock clock;

  @Test
  void deletesRefreshTokenFamiliesPastTheirExpiryAndKeepsLiveOnes() {
    UserDto user = seedUser("refresh-prune");
    OrganizationDto org = organizationService.createPersonalOrganization(user.id(), "refresh's");
    String expired = seedRefreshToken(user, org, clock.instant().minus(PAST));
    String live = seedRefreshToken(user, org, clock.instant().plus(FUTURE));

    cleanup.pruneDeadTokens();

    assertThat(refreshTokens.findByTokenHash(expired)).isEmpty();
    assertThat(refreshTokens.findByTokenHash(live)).isPresent();
  }

  // A used token inside a family that has not expired yet has to survive: presenting it a second
  // time is what proves theft and revokes the family, and a deleted row would answer with an
  // ordinary unknown-token 401 instead, losing the detection entirely.
  @Test
  void keepsAUsedRefreshTokenWhileItsFamilyIsStillLive() {
    UserDto user = seedUser("used-token");
    OrganizationDto org = organizationService.createPersonalOrganization(user.id(), "used's");
    String raw = TokenHasher.newUrlSafeToken();
    RefreshToken token =
        new RefreshToken(
            UUID.randomUUID(),
            user.id(),
            org.id(),
            TokenHasher.sha256Hex(raw),
            clock.instant().plus(FUTURE));
    token.markUsed(clock.instant());
    refreshTokens.saveAndFlush(token);

    cleanup.pruneDeadTokens();

    assertThat(refreshTokens.findByTokenHash(TokenHasher.sha256Hex(raw))).isPresent();
  }

  @Test
  void deletesConsumedAndExpiredVerificationTokensAndKeepsOutstandingOnes() {
    UserDto user = seedUser("verification-prune");
    String consumed = seedVerificationToken(user, clock.instant().plus(FUTURE), true);
    String expired = seedVerificationToken(user, clock.instant().minus(PAST), false);
    String outstanding = seedVerificationToken(user, clock.instant().plus(FUTURE), false);

    cleanup.pruneDeadTokens();

    assertThat(verificationTokens.findByTokenHash(consumed)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(expired)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(outstanding)).isPresent();
  }

  private UserDto seedUser(String slug) {
    return userService.createUnverified(slug + "@example.com", slug);
  }

  private String seedRefreshToken(UserDto user, OrganizationDto org, Instant familyExpiresAt) {
    String raw = TokenHasher.newUrlSafeToken();
    refreshTokens.saveAndFlush(
        new RefreshToken(
            UUID.randomUUID(), user.id(), org.id(), TokenHasher.sha256Hex(raw), familyExpiresAt));
    return TokenHasher.sha256Hex(raw);
  }

  private String seedVerificationToken(UserDto user, Instant expiresAt, boolean consumed) {
    String raw = TokenHasher.newUrlSafeToken();
    EmailVerificationToken token =
        new EmailVerificationToken(user.id(), TokenHasher.sha256Hex(raw), expiresAt);
    if (consumed) {
      token.consume(clock.instant());
    }
    verificationTokens.saveAndFlush(token);
    return TokenHasher.sha256Hex(raw);
  }
}
