package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.email.EmailBudgetExhaustedException;
import com.zarlania.api.email.EmailMessage;
import com.zarlania.api.email.EmailSender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the listener's two jobs: composing the right message, and never letting a send failure
 * escape. Escaping matters more than it looks — by the time either method runs the registration has
 * already committed, so an exception thrown here cannot undo anything and would only turn a
 * successful registration into a 500 for the caller.
 */
class RegistrationEmailListenerTest {

  private static final String EMAIL = "person@example.com";
  private static final String RAW_TOKEN = "the-raw-token";
  private static final String APP_BASE_URL = "https://zarlania.com";

  private final List<EmailMessage> sent = new ArrayList<>();

  // Runs the task where it was submitted, so an assertion can follow the call immediately. What is
  // under test here is which task gets submitted, not that the pool is genuinely another thread.
  private final Executor sameThread = Runnable::run;

  @Test
  void verificationEmailCarriesALinkBuiltFromTheAppBaseUrlAndTheRawToken() {
    listener(sent::add, sameThread)
        .onVerificationEmailRequested(new VerificationEmailRequested(EMAIL, RAW_TOKEN));

    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().to()).isEqualTo(EMAIL);
    assertThat(sent.getFirst().textBody())
        .contains(APP_BASE_URL + "/verify-email?token=" + RAW_TOKEN);
  }

  // The notice deliberately carries no link and no token: its recipient already has an account, and
  // whoever triggered it may not be them.
  @Test
  void duplicateAttemptNoticeGoesToTheExistingOwnerWithoutAnyToken() {
    listener(sent::add, sameThread)
        .onDuplicateRegistrationAttempted(new DuplicateRegistrationAttempted(EMAIL));

    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().to()).isEqualTo(EMAIL);
    assertThat(sent.getFirst().textBody()).doesNotContain(RAW_TOKEN).doesNotContain("verify-email");
  }

  @Test
  void aProviderRefusalIsSwallowedRatherThanFailingTheRegistrationThatAlreadyCommitted() {
    EmailSender failing =
        message -> {
          throw new IllegalStateException("provider said no");
        };

    assertThatCode(
            () ->
                listener(failing, sameThread)
                    .onVerificationEmailRequested(new VerificationEmailRequested(EMAIL, RAW_TOKEN)))
        .doesNotThrowAnyException();
  }

  @Test
  void anExhaustedEmailBudgetIsSwallowedTheSameWay() {
    EmailSender exhausted =
        message -> {
          throw new EmailBudgetExhaustedException("budget spent");
        };

    assertThatCode(
            () ->
                listener(exhausted, sameThread)
                    .onDuplicateRegistrationAttempted(new DuplicateRegistrationAttempted(EMAIL)))
        .doesNotThrowAnyException();
  }

  // A full dispatch queue must not reach the caller either. If it did, the only branch that could
  // ever see it would be the one that actually sends — which would both fail a request that had
  // already succeeded and re-open the timing channel the off-thread dispatch exists to close.
  @Test
  void aFullDispatchQueueIsSwallowedAndNothingIsSent() {
    Executor rejecting =
        task -> {
          throw new RejectedExecutionException("queue full");
        };

    assertThatCode(
            () ->
                listener(sent::add, rejecting)
                    .onVerificationEmailRequested(new VerificationEmailRequested(EMAIL, RAW_TOKEN)))
        .doesNotThrowAnyException();
    assertThat(sent).isEmpty();
  }

  // The point of the executor: nothing is sent on the calling thread. Holding the submitted task
  // unrun shows the listener has genuinely handed the work off rather than merely wrapping it.
  @Test
  void sendingIsHandedToTheExecutorRatherThanDoneOnTheCallingThread() {
    List<Runnable> submitted = new ArrayList<>();

    listener(sent::add, submitted::add)
        .onVerificationEmailRequested(new VerificationEmailRequested(EMAIL, RAW_TOKEN));

    assertThat(sent).isEmpty();
    assertThat(submitted).hasSize(1);

    submitted.getFirst().run();
    assertThat(sent).hasSize(1);
  }

  private static RegistrationEmailListener listener(EmailSender sender, Executor executor) {
    AuthProperties properties =
        new AuthProperties(
            "https://api.zarlania.com",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            Duration.ofDays(7),
            true,
            "",
            "",
            APP_BASE_URL);
    return new RegistrationEmailListener(sender, properties, executor);
  }
}
