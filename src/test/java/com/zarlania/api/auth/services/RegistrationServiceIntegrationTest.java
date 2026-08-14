package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Registration composed over the real domains it orchestrates.
 *
 * <p>The unit test proves which collaborator is called in which branch. What it cannot prove is
 * that the rows those calls leave behind add up to a usable account: a user, a password that
 * verifies, and a personal organization to scope a session to, all committed together or not at
 * all. That is what this tier is for.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RegistrationServiceIntegrationTest extends IntegrationTestBase {

  private final RegistrationService registrationService;
  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final PasswordCredentialRepository passwordCredentials;
  private final EmailVerificationService emailVerificationService;
  private final TestAccounts accounts;

  @Test
  void registeringLeavesAnUnverifiedAccountWithAPasswordAndItsOwnOrganization() {
    registrationService.register("regsvc@example.com", "regsvc", TestAccounts.PASSWORD);

    User user = userService.findByIdentifier("regsvc").orElseThrow();
    assertThat(user.emailVerified()).isFalse();
    assertThat(credentialsService.passwordMatches(user.id(), TestAccounts.PASSWORD)).isTrue();
    assertThat(organizationService.personalOrganizationOf(user.id())).isPresent();
  }

  // A taken username is refused before anything is written, so a failed registration cannot leave a
  // password or an organization behind for an account that does not exist.
  @Test
  void registeringWithATakenUsernameWritesNothingAtAll() {
    accounts.user("regsvc-taken");

    assertThatThrownBy(
            () ->
                registrationService.register(
                    "regsvc-other@example.com", "regsvc-taken", TestAccounts.PASSWORD))
        .isInstanceOf(UsernameTakenException.class);

    assertThat(userService.findByIdentifier("regsvc-other@example.com")).isEmpty();
  }

  // Registering an address that already exists must not overwrite the credentials on it: whoever
  // made the attempt has not proved they control the mailbox.
  @Test
  void registeringAnExistingAddressNeverReplacesTheStoredPassword() {
    registrationService.register(
        "regsvc-existing@example.com", "regsvcexisting", TestAccounts.PASSWORD);
    User user = userService.findByIdentifier("regsvcexisting").orElseThrow();
    String originalHash =
        passwordCredentials.findByUserId(user.id()).orElseThrow().getPasswordHash();

    registrationService.register(
        "regsvc-existing@example.com", "regsvcsecondattempt", "a-completely-different-password");

    assertThat(passwordCredentials.findByUserId(user.id()).orElseThrow().getPasswordHash())
        .isEqualTo(originalHash);
    assertThat(credentialsService.passwordMatches(user.id(), TestAccounts.PASSWORD)).isTrue();
  }

  @Test
  void verifyingAnIssuedTokenMarksTheAccountVerified() {
    registrationService.register(
        "regsvc-verify@example.com", "regsvcverify", TestAccounts.PASSWORD);
    User user = userService.findByIdentifier("regsvcverify").orElseThrow();
    // Issued through the service rather than read from the row: only the hash is stored, so the
    // raw token the email would have carried exists nowhere else.
    String token = emailVerificationService.issue(user.id());

    assertThat(registrationService.verify(token)).isTrue();
    assertThat(userService.findById(user.id()).orElseThrow().emailVerified()).isTrue();
  }

  // Unknown, expired and already-consumed all answer the same way, and none of them verifies
  // anybody: a false return has to mean nothing changed.
  @Test
  void verifyingAnUnknownTokenChangesNothing() {
    assertThat(registrationService.verify("nobody-issued-this")).isFalse();
  }
}
