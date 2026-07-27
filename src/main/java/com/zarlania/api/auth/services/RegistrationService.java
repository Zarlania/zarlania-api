package com.zarlania.api.auth.services;

import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates registration, verification and resend. Enumeration safety is deliberate: a caller
 * can never distinguish "email already registered" from "verification email sent" — nor, on resend,
 * "unknown email" from "already verified" from "unverified, email resent" — by status, response
 * body, or elapsed time. Status and body are equal by construction (every branch of {@code
 * register} and {@code resend} returns the same way). Timing equality is deliberate too: every
 * early-return branch below calls {@link CredentialsService#hashDecoyPassword()} to pay the same
 * dominant Argon2id cost the success path pays via {@link CredentialsService#createPassword},
 * because that cost (tens of milliseconds) otherwise dwarfs everything else these methods do and
 * would trivially distinguish the branches over a network. Which email actually goes out is decided
 * entirely after commit, by {@link RegistrationEmailListener}.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

  private static final String USERNAME_TAKEN_MESSAGE = "That username is taken";

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final EmailVerificationService emailVerificationService;
  private final ApplicationEventPublisher events;

  @Transactional
  public void register(String email, String username, String rawPassword) {
    if (userService.usernameExists(username)) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN, USERNAME_TAKEN_MESSAGE);
    }
    if (userService.emailExists(email)) {
      credentialsService.hashDecoyPassword();
      events.publishEvent(new DuplicateRegistrationAttempted(email));
      return;
    }
    UserDto user = userService.createUnverified(email, username);
    credentialsService.createPassword(user.id(), rawPassword);
    organizationService.createPersonalOrganization(user.id(), username);
    String rawToken = emailVerificationService.issue(user.id());
    events.publishEvent(new VerificationEmailRequested(email, rawToken));
  }

  @Transactional
  public boolean verify(String rawToken) {
    Optional<UUID> userId = emailVerificationService.consume(rawToken);
    userId.ifPresent(userService::markEmailVerified);
    return userId.isPresent();
  }

  @Transactional
  public void resend(String email) {
    // Unconditional, not just on the branches that would otherwise skip it: resend has no
    // "real" Argon2 work of its own (unlike register's createPassword) for a decoy to stand in
    // for, so every outcome — unknown email, already verified, resent — needs the same paid-up-
    // front cost to stay indistinguishable from each other.
    credentialsService.hashDecoyPassword();
    userService
        .findByIdentifier(email)
        .filter(user -> !user.emailVerified())
        .ifPresent(
            user -> {
              String rawToken = emailVerificationService.issue(user.id());
              events.publishEvent(new VerificationEmailRequested(email, rawToken));
            });
  }
}
