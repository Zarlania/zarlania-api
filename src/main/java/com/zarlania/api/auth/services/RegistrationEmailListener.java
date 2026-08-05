package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.email.EmailDispatcher;
import com.zarlania.api.email.EmailMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Composes the two emails registration sends, and hands each to {@link EmailDispatcher}.
 *
 * <p>Only composition lives here. Which thread a send happens on, which failures are possible and
 * how each is reported all belong to the outbound channel, so this class neither knows nor can
 * affect them — see {@link EmailDispatcher#dispatch}.
 *
 * <p>Both listeners fire on {@code AFTER_COMMIT}, never inside the transaction: a failing email
 * provider must not roll back a registration that otherwise succeeded, and a verification link must
 * never be emailed for a row that was rolled back.
 */
@Component
@RequiredArgsConstructor
public class RegistrationEmailListener {

  private static final String VERIFICATION_EMAIL_SUBJECT = "Verify your Zarlania account";
  private static final String DUPLICATE_ATTEMPT_SUBJECT =
      "Someone tried to register with your email";
  private static final String VERIFY_EMAIL_PATH = "/verify-email";
  private static final String TOKEN_QUERY_PARAM = "token";

  private final EmailDispatcher emailDispatcher;
  private final AuthProperties authProperties;

  /**
   * Sends the verification email once the registration that asked for it has committed, so a rolled
   * back registration never emails a link to a row that does not exist.
   *
   * <p>{@code fallbackExecution} because this event has two publishers with different transaction
   * shapes. Registration publishes inside its own transaction, so the send is deferred to {@code
   * AFTER_COMMIT} as normal; resend publishes with no transaction active — its only write, the new
   * token, has already committed in {@code EmailVerificationService}'s own — and without the flag
   * Spring would silently drop the event there, losing every resent verification email.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onVerificationEmailRequested(VerificationEmailRequested event) {
    String verificationUrl =
        authProperties.appBaseUrl()
            + VERIFY_EMAIL_PATH
            + "?"
            + TOKEN_QUERY_PARAM
            + "="
            + event.rawToken();
    emailDispatcher.dispatch(
        new EmailMessage(
            event.email(),
            VERIFICATION_EMAIL_SUBJECT,
            "Click the link below to verify your Zarlania account:\n\n" + verificationUrl,
            event.userId().toString()));
  }

  /**
   * Tells the existing owner that someone tried to register their address again. Carries no token
   * and no link: whoever made the attempt has not proved they control the mailbox.
   *
   * <p>{@code fallbackExecution} here too, because registration is not itself
   * {@code @Transactional}: its transaction lives in {@code AccountCreator}, and the branch that
   * publishes this event never enters it — the account already exists, so there is nothing to
   * create. With no transaction active at publication, Spring would silently discard the event and
   * the notice would never be sent.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onDuplicateRegistrationAttempted(DuplicateRegistrationAttempted event) {
    emailDispatcher.dispatch(
        new EmailMessage(
            event.email(),
            DUPLICATE_ATTEMPT_SUBJECT,
            "Someone tried to register a new Zarlania account with this email address. If this"
                + " was not you, no action is needed — your existing account is safe. If it was"
                + " you, sign in with your existing account instead.",
            event.userId().toString()));
  }
}
