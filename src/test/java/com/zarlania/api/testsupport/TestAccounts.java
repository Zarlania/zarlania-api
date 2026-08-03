package com.zarlania.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.entities.EmailVerificationToken;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Seeds accounts and the rows that hang off them, for tests below the HTTP layer.
 *
 * <p>Every integration test that needs an account previously grew its own {@code seedUser} and
 * {@code seedRefreshToken}, which meant the shape of a test account was defined in as many places
 * as there were tests, and adding a table to the account graph meant finding all of them.
 *
 * <p>Seeds through the owning domain's service wherever one exists, so what a test creates goes
 * through the same code a request would and cannot drift into a state the application never
 * produces. Tokens are the exception — a test needs to control expiry and consumption directly, and
 * no service exposes that.
 *
 * <p>Registered by {@link TestSupportConfig}, so a test injects this rather than building it.
 */
@TestComponent
@RequiredArgsConstructor
public class TestAccounts {

  /** The password every seeded account is created with. */
  public static final String PASSWORD = "correct-horse-battery";

  private final UserService users;
  private final CredentialsService credentials;
  private final OrganizationService organizations;
  private final RefreshTokenRepository refreshTokens;
  private final EmailVerificationTokenRepository verificationTokens;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  // Field-injected rather than constructor-injected: @PersistenceContext supplies the
  // transaction-bound proxy, which a plain constructor parameter would not.
  @PersistenceContext private EntityManager entityManager;

  /**
   * One seeded account and the ids or hashes a test needs to assert on what became of it.
   *
   * @param verificationTokenHash the hash of an outstanding verification token
   * @param refreshTokenHash the hash of a live refresh token, as a logged-in account would have
   */
  public record SeededAccount(
      UUID userId,
      UUID organizationId,
      String email,
      String username,
      String verificationTokenHash,
      String refreshTokenHash) {}

  /**
   * An account with nothing hanging off it: no password, no organization, no tokens. For tests
   * about the users domain alone.
   *
   * @param slug becomes both the username and the local part of the address, so one call cannot
   *     collide with another
   */
  public UserDto user(String slug) {
    return users.createUnverified(slug + "@example.com", slug);
  }

  /** An account with a password and its personal organization, as registration would leave it. */
  public UserDto userWithPassword(String slug) {
    UserDto user = user(slug);
    credentials.createPassword(user.id(), PASSWORD);
    organizations.createPersonalOrganization(user.id(), slug + "'s Space");
    return user;
  }

  /**
   * A whole account graph: user, password, personal organization, an outstanding verification token
   * and a live refresh token. What a purge has to remove in full, and what it must leave alone.
   *
   * @param verified whether the address has been proved, which is what decides eligibility for a
   *     purge
   */
  public SeededAccount fullAccount(String slug, boolean verified) {
    UserDto user = user(slug);
    credentials.createPassword(user.id(), PASSWORD);
    OrganizationDto organization =
        organizations.createPersonalOrganization(user.id(), slug + "'s Space");
    String verificationHash = verificationToken(user.id(), farFuture(), false);
    String refreshHash = refreshToken(user.id(), organization.id(), farFuture());
    if (verified) {
      users.markEmailVerified(user.id());
    }
    return new SeededAccount(
        user.id(), organization.id(), user.email(), user.username(), verificationHash, refreshHash);
  }

  /**
   * Writes a refresh token directly, so a test can choose its family expiry.
   *
   * @return the token's hash, which is what a test looks it up by — the raw value is discarded,
   *     since nothing here redeems it
   */
  public String refreshToken(UUID userId, UUID organizationId, Instant familyExpiresAt) {
    String raw = TokenHasher.newUrlSafeToken();
    refreshTokens.saveAndFlush(
        new RefreshToken(
            UUID.randomUUID(),
            userId,
            organizationId,
            TokenHasher.sha256Hex(raw),
            familyExpiresAt));
    return TokenHasher.sha256Hex(raw);
  }

  /**
   * Writes a verification token directly, so a test can choose its expiry and whether it has been
   * consumed — neither of which {@code EmailVerificationService} lets a caller set.
   *
   * @return the token's hash
   */
  public String verificationToken(UUID userId, Instant expiresAt, boolean consumed) {
    String raw = TokenHasher.newUrlSafeToken();
    EmailVerificationToken token =
        new EmailVerificationToken(userId, TokenHasher.sha256Hex(raw), expiresAt);
    if (consumed) {
      token.consume(clock.instant());
    }
    verificationTokens.saveAndFlush(token);
    return TokenHasher.sha256Hex(raw);
  }

  /**
   * Ages an account by rewriting {@code created_at} in SQL.
   *
   * <p>In SQL because the column is {@code updatable = false}: Hibernate will not move it, which is
   * the property that makes it trustworthy in production and the reason a test cannot set it the
   * ordinary way. The alternative — waiting seven days — is not one.
   */
  public void backdateCreatedAt(UUID userId, Duration age) {
    // Inside a transactional test the account's insert is still queued in the persistence context,
    // so this UPDATE would match no row and fail silently — the account would simply not be old.
    // Flushed first, and only when a transaction is actually active, since flushing without one
    // throws. The affected-row count is asserted rather than ignored, so a future variant of this
    // problem fails here instead of surfacing as an unrelated empty result.
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      entityManager.flush();
    }
    int updated =
        jdbcTemplate.update(
            "UPDATE users SET created_at = ? WHERE id = ?",
            Timestamp.from(clock.instant().minus(age)),
            userId);
    assertThat(updated).as("backdated user rows").isEqualTo(1);
  }

  private Instant farFuture() {
    return clock.instant().plus(Duration.ofDays(30));
  }
}
