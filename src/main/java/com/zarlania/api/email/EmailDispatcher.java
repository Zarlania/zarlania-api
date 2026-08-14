package com.zarlania.api.email;

import com.zarlania.api.email.exceptions.EmailBudgetExhaustedException;
import com.zarlania.api.logging.LogSanitizer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The way anything in this application sends email: off the request thread, and never able to fail
 * the work that asked for it.
 *
 * <p>Callers compose a message and hand it here. Everything else about sending — which thread it
 * happens on, which failures are possible, how each is reported — belongs to the outbound channel
 * rather than to whatever triggered the send, so no caller has to know any of it. That is the point
 * of this class existing rather than each caller wrapping {@link EmailSender} itself: the second
 * caller would otherwise copy a try/catch, three log markers and two static-analysis suppressions,
 * and the copies would drift.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatcher {

  // Greppable prefixes on the three ways a send can fail, kept distinct because the response to
  // each differs: the budget being exhausted means this service stopped itself and the cap needs
  // re-deriving, a provider refusal means something outside it broke, and a full queue means the
  // message never reached the sender at all. Every one of them leaves somebody waiting for an email
  // that will never arrive, so all three are errors rather than warnings.
  private static final String BUDGET_EXHAUSTED_MARKER = "EMAIL_BUDGET_EXHAUSTED";
  private static final String SEND_FAILED_MARKER = "EMAIL_SEND_FAILED";
  private static final String QUEUE_FULL_MARKER = "EMAIL_QUEUE_FULL";

  private final EmailSender emailSender;

  // EmailConfig's dispatch pool, not Spring Boot's own applicationTaskExecutor — hence the
  // @Qualifier, which lombok.config copies onto the generated constructor parameter.
  @Qualifier(EmailConfig.DISPATCH_EXECUTOR_BEAN)
  private final Executor emailDispatchExecutor;

  /**
   * Queues a message for sending and returns immediately. Never throws.
   *
   * <p>Handed to a pool rather than sent here, because "here" is the request thread. A caller that
   * sends after committing — a {@code @TransactionalEventListener} on {@code AFTER_COMMIT}, in
   * practice — still runs on whichever thread published the event: the commit has happened, the
   * response has not been written, so an inline provider round trip lands squarely in the caller's
   * measured response time. That matters wherever only some branches send anything, since the
   * difference would make which branch was taken measurable from outside.
   *
   * <p>Nothing propagates, for the same reason plus a simpler one: by the time a send is queued the
   * work that asked for it has already committed, so an exception here could not undo it and would
   * only turn a success into a 500. Failures are logged under the markers above instead.
   */
  public void dispatch(EmailMessage message) {
    try {
      emailDispatchExecutor.execute(() -> sendSafely(message));
    } catch (RejectedExecutionException exception) {
      logFailure(QUEUE_FULL_MARKER, message, exception);
    }
  }

  /**
   * {@link EmailSender} is a port: its implementations — a Resend HTTP client today, something else
   * tomorrow — throw whatever unchecked exception their own client library uses, and the interface
   * declares none. {@link RuntimeException} is the only type narrow enough to still mean "an email
   * failure" and broad enough to cover every current and future implementation, which is why this
   * catch is deliberately wider than Checkstyle's {@code IllegalCatch} rule allows.
   *
   * <p>The two failure modes are caught separately so each gets its own marker: a budget rejection
   * is this service stopping itself, a provider refusal is something outside it breaking.
   */
  @SuppressWarnings("checkstyle:IllegalCatch")
  private void sendSafely(EmailMessage message) {
    try {
      emailSender.send(message);
    } catch (EmailBudgetExhaustedException exception) {
      logFailure(BUDGET_EXHAUSTED_MARKER, message, exception);
    } catch (RuntimeException exception) {
      logFailure(SEND_FAILED_MARKER, message, exception);
    }
  }

  /**
   * Records a send that will never arrive, without recording who it was for.
   *
   * <p>Logs {@link EmailMessage#reference()} rather than the recipient. The address identifies a
   * person and the body carries the raw verification token, so neither may appear in a log an
   * operator, a log shipper, or a support ticket can end up holding. The reference is the account
   * id its caller supplied, which an operator with database access can resolve and nobody else can
   * — the same information, reachable only by someone already entitled to it.
   *
   * <p>The reference and subject are folded onto one line first. Both are supplied by the calling
   * domain rather than by this package, so a value that reached one of them from request input
   * could otherwise contain {@code \r\n} and forge extra log lines.
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Reference and subject are both passed through LogSanitizer.singleLine before"
              + " reaching log.error; FindSecBugs loses the sanitizer across the interprocedural"
              + " hop, not because the value is actually unsanitized (see LoggingEmailSender#send"
              + " for the unflagged single-hop control case using the identical helper).")
  private void logFailure(String marker, EmailMessage message, RuntimeException cause) {
    log.error(
        "{}: email for {} was not sent (subject: {})",
        marker,
        LogSanitizer.singleLine(message.reference()),
        LogSanitizer.singleLine(message.subject()),
        cause);
  }
}
