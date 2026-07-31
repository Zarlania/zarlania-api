package com.zarlania.api.auth.services;

import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the four rows a new account is made of — user, password credential, personal organization
 * and verification token — inside a single transaction.
 *
 * <p>Split out from {@link RegistrationService} rather than left as a {@code @Transactional} method
 * there, for the same reason {@code UnverifiedAccountPurger} is a separate bean: the transaction
 * boundary has to be somewhere the caller can stand <em>outside</em> of. Both uniqueness rules on
 * {@code users} are ultimately enforced by the database, and when a concurrent registration loses
 * that race the resulting {@code DataIntegrityViolationException} has to be turned back into an
 * ordinary answer <em>after</em> this transaction has rolled back. A {@code try}/{@code catch}
 * inside the transaction could not do that — by the time the exception surfaces the transaction is
 * already marked rollback-only, so anything the handler wrote would be discarded and the commit
 * would fail anyway. Calling this through Spring's normal inter-bean proxy puts {@link
 * RegistrationService#register} genuinely outside the boundary; a self-invocation would bypass the
 * proxy and open no transaction at all.
 */
@Service
@RequiredArgsConstructor
class AccountCreator {

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final EmailVerificationService emailVerificationService;
  private final ApplicationEventPublisher events;

  // The verification token is issued and its event published in here rather than by the caller, so
  // that a crash between "user exists" and "user has a way to verify" is impossible: either all
  // four rows commit or none do. The event is published inside the transaction on purpose —
  // RegistrationEmailListener is an AFTER_COMMIT listener, so the email goes out only if the
  // account it points at actually survived.
  @Transactional
  void createAccount(String email, String username, String rawPassword) {
    UserDto user = userService.createUnverified(email, username);
    credentialsService.createPassword(user.id(), rawPassword);
    organizationService.createPersonalOrganization(user.id(), username);
    String rawToken = emailVerificationService.issue(user.id());
    events.publishEvent(new VerificationEmailRequested(email, rawToken));
  }
}
