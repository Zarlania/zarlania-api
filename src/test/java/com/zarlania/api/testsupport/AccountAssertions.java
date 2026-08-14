package com.zarlania.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import com.zarlania.api.users.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

/**
 * Asserts that a seeded account is either wholly gone or wholly intact, across every table it
 * touches.
 *
 * <p>Wholly is the point, and it is why this is shared rather than written per test. A purge that
 * removes the user but leaves a membership row behind, or that rolls back the user while leaving a
 * credential deleted, is a bug that a per-table assertion in one test will not catch and a
 * half-written copy in another will miss entirely. Adding a table to the account graph means
 * changing this class, and every test then covers it.
 *
 * <p>Registered by {@link TestSupportConfig}.
 */
@TestComponent
@RequiredArgsConstructor
public class AccountAssertions {

  private final UserRepository users;
  private final PasswordCredentialRepository passwordCredentials;
  private final MembershipRepository memberships;
  private final OrganizationRepository organizations;
  private final EmailVerificationTokenRepository verificationTokens;
  private final RefreshTokenRepository refreshTokens;

  /** Asserts that nothing belonging to the account survives, in any table. */
  public void assertFullyGone(SeededAccount account) {
    assertThat(users.findById(account.userId())).as("user row").isEmpty();
    assertThat(passwordCredentials.findByUserId(account.userId())).as("password").isEmpty();
    assertThat(memberships.findByUserId(account.userId())).as("memberships").isEmpty();
    assertThat(organizations.findById(account.organizationId()))
        .as("personal organization")
        .isEmpty();
    assertThat(verificationTokens.findByTokenHash(account.verificationTokenHash()))
        .as("verification token")
        .isEmpty();
    assertThat(refreshTokens.findByTokenHash(account.refreshTokenHash()))
        .as("refresh token")
        .isEmpty();
  }

  /**
   * Asserts that every row belonging to the account is still there — the check that a rolled-back
   * purge really did put everything back, not merely that the user row survived.
   */
  public void assertFullyIntact(SeededAccount account) {
    assertThat(users.findById(account.userId())).as("user row").isPresent();
    assertThat(passwordCredentials.findByUserId(account.userId())).as("password").isPresent();
    assertThat(memberships.findByUserId(account.userId())).as("memberships").isNotEmpty();
    assertThat(organizations.findById(account.organizationId()))
        .as("personal organization")
        .isPresent();
    assertThat(verificationTokens.findByTokenHash(account.verificationTokenHash()))
        .as("verification token")
        .isPresent();
    assertThat(refreshTokens.findByTokenHash(account.refreshTokenHash()))
        .as("refresh token")
        .isPresent();
  }
}
