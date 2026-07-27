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
 * can never distinguish "email already registered" from "verification email sent" by status or
 * response body — both paths return 202 with no body, and the difference is which email goes out,
 * handled entirely after commit by {@link RegistrationEmailListener}. This does not extend to
 * timing: the duplicate-email branch below does one lookup and returns, while the success branch
 * creates a user, credentials, an organization and a token, so the two are distinguishable by
 * elapsed time. Closing that gap would mean doing throwaway work on the duplicate path to match,
 * which the brief this service implements does not ask for.
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
