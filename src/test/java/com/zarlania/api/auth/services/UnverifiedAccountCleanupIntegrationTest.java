package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.common.security.TokenHasher;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.repositories.UserRepository;
import com.zarlania.api.users.services.UserService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class UnverifiedAccountCleanupIntegrationTest {

  // Comfortably past unverified-account-max-age (P7D in application.yml) without depending on the
  // exact configured duration, so this test still makes sense if that value ever changes.
  private static final Duration EXPIRED_AGE = Duration.ofDays(8);
  private static final Duration REFRESH_FAMILY_LIFETIME = Duration.ofDays(30);
  private static final String RAW_PASSWORD = "correct horse battery staple";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  // A spy, not a plain @Autowired field: the resilience test below needs to force exactly one
  // user's deletion to fail mid-transaction while every other call passes through to the real
  // repository, which only Mockito's spy/stub machinery can do against a live Postgres-backed bean.
  @MockitoSpyBean private RefreshTokenRepository refreshTokens;

  private final UnverifiedAccountCleanup cleanup;
  private final UserRepository users;
  private final UserService userService;
  private final CredentialsService credentialsService;
  private final PasswordCredentialRepository passwordCredentials;
  private final OrganizationService organizationService;
  private final MembershipRepository memberships;
  private final OrganizationRepository organizations;
  private final EmailVerificationService emailVerificationService;
  private final EmailVerificationTokenRepository verificationTokens;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  private record SeededAccount(
      UUID userId, UUID organizationId, String verificationTokenHash, String refreshTokenHash) {}

  @Test
  void purgesAnExpiredUnverifiedAccountAndAllItsDependentRowsButLeavesOthersUntouched() {
    SeededAccount expired = seedAccount("expired-unverified", false);
    backdateCreatedAt(expired.userId(), EXPIRED_AGE);
    SeededAccount fresh = seedAccount("fresh-unverified", false);
    SeededAccount verified = seedAccount("long-verified", true);
    backdateCreatedAt(verified.userId(), EXPIRED_AGE);

    cleanup.purgeExpiredUnverifiedAccounts();

    assertAccountIsFullyGone(expired);
    assertAccountIsFullyIntact(fresh);
    assertAccountIsFullyIntact(verified);
  }

  // Forces RefreshTokenRepository.deleteByUserId to throw for exactly one of two expired users.
  // Two things must hold if purgeOneAccount really runs in its own transaction, not just as a
  // best-effort loop body: the failing user's *earlier* deletes in that same method (verification
  // token, password credential) must have rolled back rather than stuck half-applied, and the
  // sweep must still finish the other, healthy expired user rather than aborting outright.
  @Test
  void oneUsersDeletionFailureRollsBackOnlyThatUserAndDoesNotAbortTheSweep() {
    SeededAccount poisoned = seedAccount("poisoned-expired", false);
    backdateCreatedAt(poisoned.userId(), EXPIRED_AGE);
    SeededAccount healthy = seedAccount("healthy-expired", false);
    backdateCreatedAt(healthy.userId(), EXPIRED_AGE);
    doThrow(new RuntimeException("simulated failure"))
        .when(refreshTokens)
        .deleteByUserId(poisoned.userId());

    cleanup.purgeExpiredUnverifiedAccounts();

    assertAccountIsFullyIntact(poisoned);
    assertAccountIsFullyGone(healthy);
  }

  private SeededAccount seedAccount(String slug, boolean verified) {
    UserDto user = userService.createUnverified(slug + "@example.com", slug);
    credentialsService.createPassword(user.id(), RAW_PASSWORD);
    OrganizationDto org =
        organizationService.createPersonalOrganization(user.id(), slug + "'s Space");
    String rawVerificationToken = emailVerificationService.issue(user.id());
    String rawRefreshToken = TokenHasher.newUrlSafeToken();
    refreshTokens.saveAndFlush(
        new RefreshToken(
            UUID.randomUUID(),
            user.id(),
            org.id(),
            TokenHasher.sha256Hex(rawRefreshToken),
            clock.instant().plus(REFRESH_FAMILY_LIFETIME)));
    if (verified) {
      userService.markEmailVerified(user.id());
    }
    return new SeededAccount(
        user.id(),
        org.id(),
        TokenHasher.sha256Hex(rawVerificationToken),
        TokenHasher.sha256Hex(rawRefreshToken));
  }

  private void backdateCreatedAt(UUID userId, Duration age) {
    Instant backdated = clock.instant().minus(age);
    jdbcTemplate.update(
        "UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(backdated), userId);
  }

  private void assertAccountIsFullyGone(SeededAccount account) {
    assertThat(users.findById(account.userId())).isEmpty();
    assertThat(passwordCredentials.findByUserId(account.userId())).isEmpty();
    assertThat(memberships.findByUserId(account.userId())).isEmpty();
    assertThat(organizations.findById(account.organizationId())).isEmpty();
    assertThat(verificationTokens.findByTokenHash(account.verificationTokenHash())).isEmpty();
    assertThat(refreshTokens.findByTokenHash(account.refreshTokenHash())).isEmpty();
  }

  private void assertAccountIsFullyIntact(SeededAccount account) {
    assertThat(users.findById(account.userId())).isPresent();
    assertThat(passwordCredentials.findByUserId(account.userId())).isPresent();
    assertThat(memberships.findByUserId(account.userId())).isNotEmpty();
    assertThat(organizations.findById(account.organizationId())).isPresent();
    assertThat(verificationTokens.findByTokenHash(account.verificationTokenHash())).isPresent();
    assertThat(refreshTokens.findByTokenHash(account.refreshTokenHash())).isPresent();
  }
}
