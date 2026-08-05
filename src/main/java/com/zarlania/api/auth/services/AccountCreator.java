package com.zarlania.api.auth.services;

import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
class AccountCreator {

  private static final String REGISTERED_MARKER = "ACCOUNT_REGISTERED";

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final EmailVerificationService emailVerificationService;
  private final ApplicationEventPublisher applicationEventPublisher;

  /**
   * Writes all four rows and publishes the event that sends the verification email.
   *
   * <p>The verification token is issued and its event published in here rather than by the caller,
   * so that a crash between "user exists" and "user has a way to verify" is impossible: either all
   * four rows commit or none do. The event is published inside the transaction on purpose — {@link
   * RegistrationEmailListener} is an {@code AFTER_COMMIT} listener, so the email goes out only if
   * the account it points at actually survived.
   *
   * @param rawPassword the password as the caller typed it; {@link CredentialsService} hashes it,
   *     and it is never stored or logged in this form
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent registration
   *     claimed the email or username first. It surfaces at commit, so only a caller outside this
   *     transaction can catch it — see this class's own documentation.
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  @Transactional
  void createAccount(String email, String username, String rawPassword) {
    User user = userService.createUnverified(email, username);
    credentialsService.createPassword(user.id(), rawPassword);
    organizationService.createPersonalOrganization(user.id(), username);
    String rawToken = emailVerificationService.issue(user.id());
    // Logged before the event rather than after: this line is the record that all four rows were
    // written, and it belongs to the transaction that wrote them. The id alone, so that neither
    // the address, the token, nor the chosen name has to appear.
    log.info("{}: created account {}", REGISTERED_MARKER, user.id());
    applicationEventPublisher.publishEvent(
        new VerificationEmailRequested(email, rawToken, user.id()));
  }
}
