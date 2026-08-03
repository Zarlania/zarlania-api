package com.zarlania.api.credentials.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passwords as they are actually stored, and as they actually verify.
 *
 * <p>The unit test asserts that the encoder is called; only a real round trip shows that what came
 * back out of Postgres still verifies. That is the failure this tier exists to catch — a hash
 * truncated by a column, or an encoding that survives in memory and not on disk.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class CredentialsServiceIntegrationTest extends IntegrationTestBase {

  private final CredentialsService credentialsService;
  private final PasswordCredentialRepository passwordCredentials;
  private final EmailVerificationTokenRepository verificationTokens;
  private final TestAccounts accounts;
  private final Clock clock;

  @Test
  void aStoredPasswordStillVerifiesAfterARoundTripThroughTheDatabase() {
    UserDto user = accounts.user("cred-roundtrip");

    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);

    assertThat(credentialsService.passwordMatches(user.id(), TestAccounts.PASSWORD)).isTrue();
    assertThat(credentialsService.passwordMatches(user.id(), "not-the-password")).isFalse();
  }

  // The raw password must never reach the column: a database disclosure has to yield an Argon2 hash
  // and nothing that can be presented.
  @Test
  void whatIsStoredIsAnArgonHashRatherThanThePassword() {
    UserDto user = accounts.user("cred-hashed");

    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);

    String stored = passwordCredentials.findByUserId(user.id()).orElseThrow().getPasswordHash();
    assertThat(stored).doesNotContain(TestAccounts.PASSWORD).startsWith("$argon2id$");
  }

  // Indistinguishable from a wrong password, deliberately: an account with no password at all must
  // not be discoverable through the answer to a login attempt.
  @Test
  void anAccountWithNoPasswordNeverMatches() {
    UserDto user = accounts.user("cred-none");

    assertThat(credentialsService.passwordMatches(user.id(), TestAccounts.PASSWORD)).isFalse();
  }

  // Both tables in one call, because a caller purging an account must not have to know that proof
  // material is split across two of them.
  @Test
  void deleteAllForUserClearsBothTheCredentialAndEveryVerificationToken() {
    UserDto user = accounts.user("cred-purge");
    credentialsService.createPassword(user.id(), TestAccounts.PASSWORD);
    String tokenHash =
        accounts.verificationToken(user.id(), clock.instant().plus(Duration.ofDays(1)), false);

    credentialsService.deleteAllForUser(user.id());

    assertThat(passwordCredentials.findByUserId(user.id())).isEmpty();
    assertThat(verificationTokens.findByTokenHash(tokenHash)).isEmpty();
  }

  // Nothing is written and no account is touched — the whole point is to spend the time, so that a
  // branch with nothing to hash cannot be told apart from one that hashes.
  @Test
  void hashingADecoyLeavesNoTraceInTheDatabase() {
    UserDto user = accounts.user("cred-decoy");

    credentialsService.hashDecoyPassword();

    assertThat(passwordCredentials.findByUserId(user.id())).isEmpty();
  }
}
