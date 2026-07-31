package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.common.email.EmailBudgetExhaustedException;
import com.zarlania.api.common.email.EmailConfig;
import com.zarlania.api.common.email.EmailMessage;
import com.zarlania.api.common.email.EmailSender;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends registration email after the triggering transaction commits, never inside it — a failing
 * email provider must not roll back a registration that otherwise succeeded. By the time these
 * methods run, the user (and, for verification, the personal organization and token) already exist;
 * a send failure here cannot undo that, so it is caught and logged rather than allowed to
 * propagate. {@code EmailSender} implementations such as {@code ResendEmailSender} do throw on a
 * non-2xx response, and letting that escape an {@code AFTER_COMMIT} listener would otherwise be the
 * only trace of a lost verification email.
 *
 * <p>Both listeners hand the actual send to {@code EmailConfig}'s dispatch pool rather than perform
 * it themselves. "After commit" is not "after the response": an {@code AFTER_COMMIT} listener still
 * runs on the publishing thread, which here is the request thread, so an inline provider round trip
 * would be paid for by the caller in measurable latency. See {@link #dispatch}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationEmailListener {

  private static final String VERIFICATION_EMAIL_SUBJECT = "Verify your Zarlania account";
  private static final String DUPLICATE_ATTEMPT_SUBJECT =
      "Someone tried to register with your email";
  private static final String VERIFY_EMAIL_PATH = "/verify-email";
  private static final String TOKEN_QUERY_PARAM = "token";

  // Greppable prefixes on the two ways a send can fail, kept distinct because the responses differ:
  // the budget being exhausted means this service stopped itself and the cap needs re-deriving,
  // while a provider refusal means something outside it broke. Either way a user is waiting for an
  // email that will never arrive, so both are errors, not warnings.
  private static final String BUDGET_EXHAUSTED_MARKER = "EMAIL_BUDGET_EXHAUSTED";
  private static final String SEND_FAILED_MARKER = "EMAIL_SEND_FAILED";
  // The third way, distinct from the two above because it means the dispatch queue is backed up
  // rather than the provider or the budget having refused: the message never reached the sender.
  private static final String QUEUE_FULL_MARKER = "EMAIL_QUEUE_FULL";

  private final EmailSender emailSender;
  private final AuthProperties authProperties;

  // The dispatch pool from EmailConfig, not Spring Boot's own applicationTaskExecutor — hence the
  // @Qualifier, which lombok.config copies onto the generated constructor parameter.
  @Qualifier(EmailConfig.DISPATCH_EXECUTOR_BEAN)
  private final Executor emailDispatchExecutor;

  // fallbackExecution, unlike the duplicate-notice listener below, because this event has two
  // publishers with different transaction shapes. RegistrationService.register publishes inside its
  // own transaction, so the send is deferred to AFTER_COMMIT as normal; resend publishes with no
  // transaction active — its only write, the new token, has already committed in
  // EmailVerificationService's own — and without this flag Spring would silently drop the event
  // there, losing every resent verification email. The duplicate notice has only the transactional
  // publisher, so it needs no fallback.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onVerificationEmailRequested(VerificationEmailRequested event) {
    String verificationUrl =
        authProperties.appBaseUrl()
            + VERIFY_EMAIL_PATH
            + "?"
            + TOKEN_QUERY_PARAM
            + "="
            + event.rawToken();
    dispatch(
        event.email(),
        new EmailMessage(
            event.email(),
            VERIFICATION_EMAIL_SUBJECT,
            "Click the link below to verify your Zarlania account:\n\n" + verificationUrl));
  }

  // fallbackExecution here too, now that RegistrationService.register is no longer @Transactional
  // itself: its transaction lives in AccountCreator, and the branch that publishes this event never
  // enters it — the account already exists, so there is nothing to create. With no transaction
  // active at publication time, Spring would silently discard the event and the duplicate-attempt
  // notice would never be sent.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onDuplicateRegistrationAttempted(DuplicateRegistrationAttempted event) {
    dispatch(
        event.email(),
        new EmailMessage(
            event.email(),
            DUPLICATE_ATTEMPT_SUBJECT,
            "Someone tried to register a new Zarlania account with this email address. If this"
                + " was not you, no action is needed — your existing account is safe. If it was"
                + " you, sign in with your existing account instead."));
  }

  // EmailSender is a port (CLAUDE.md's "infra plug-and-play"): its implementations — a Resend
  // HTTP client today, something else tomorrow — throw whatever unchecked exception their own
  // client library uses, and the interface declares none. RuntimeException is the only type
  // narrow enough to still be "an email failure" and broad enough to cover every current and
  // future implementation, which is why this catch is deliberately wider than Checkstyle's
  // IllegalCatch default allows.
  //
  // The two failure modes are caught separately so each gets its own marker: a budget rejection
  // is this service stopping itself, a provider refusal is something outside it breaking.
  @SuppressWarnings("checkstyle:IllegalCatch")
  private void sendSafely(String recipient, EmailMessage message) {
    try {
      emailSender.send(message);
    } catch (EmailBudgetExhaustedException e) {
      logFailure(BUDGET_EXHAUSTED_MARKER, recipient, message, e);
    } catch (RuntimeException e) {
      logFailure(SEND_FAILED_MARKER, recipient, message, e);
    }
  }

  // The send is handed to EmailConfig's dispatch pool rather than run here, because "here" is the
  // request thread. An AFTER_COMMIT listener still runs synchronously on whichever thread published
  // the event — the commit has happened, but the response has not been written yet — so an inline
  // provider round trip lands squarely in the caller's measured response time. That matters most on
  // /auth/resend, where only one of the three outcomes publishes an event at all: leaving the send
  // inline would make "this email belongs to an unverified account" measurable from the outside,
  // undoing the decoy hash RegistrationService pays on every branch to prevent exactly that.
  //
  // Rejection is caught rather than allowed to escape, for the same reason. A full queue throwing
  // back into the publisher would both surface a 500 for a registration that already committed and
  // restore the timing difference, since only the sending branch could ever see it.
  private void dispatch(String recipient, EmailMessage message) {
    try {
      emailDispatchExecutor.execute(() -> sendSafely(recipient, message));
    } catch (RejectedExecutionException e) {
      logFailure(QUEUE_FULL_MARKER, recipient, message, e);
    }
  }

  // Never logs the message body: it carries the raw verification token for the verification
  // email, and CLAUDE.md forbids logging that. The recipient and subject are enough to find and
  // manually resend the specific email a provider outage dropped. Both are stripped of line
  // breaks first — the recipient is caller-supplied (registration input), so a crafted value
  // containing "\r\n" could otherwise forge extra log lines.
  //
  // CRLF_INJECTION_LOGS still fires here even so: FindSecBugs's interprocedural taint tracker
  // loses the sanitizer once the value has passed through the event record's accessor and this
  // method's own stripLineBreaks() helper on the way to the varargs Logger.error(String,
  // Object..., Throwable) overload. LoggingEmailSender#send uses the exact same
  // replace("\r","").replace("\n"," ") pattern one hop earlier (straight from the field to
  // log.info) and is not flagged, which is the control case confirming the sanitizer itself is
  // sound — this is a detector depth limit, not a real CRLF hole.
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "recipient and message.subject() are both passed through stripLineBreaks before"
              + " reaching log.error; FindSecBugs loses the sanitizer across the extra"
              + " interprocedural hop through the event record accessor, not because the value"
              + " is actually unsanitized (see LoggingEmailSender#send for the unflagged"
              + " single-hop control case using the identical pattern).")
  private void logFailure(
      String marker, String recipient, EmailMessage message, RuntimeException cause) {
    log.error(
        "{}: registration email to {} was not sent (subject: {})",
        marker,
        stripLineBreaks(recipient),
        stripLineBreaks(message.subject()),
        cause);
  }

  private static String stripLineBreaks(String value) {
    return value.replace("\r", "").replace("\n", " ");
  }
}
