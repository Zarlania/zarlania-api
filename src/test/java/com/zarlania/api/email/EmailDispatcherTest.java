package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zarlania.api.email.exceptions.EmailBudgetExhaustedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * The two guarantees the dispatcher makes to every caller: nothing is sent on the calling thread,
 * and nothing thrown by a send ever reaches the caller.
 *
 * <p>Both matter more than they look. A caller sends after its own work has committed, so an
 * exception escaping here could not undo anything and would only turn a success into a 500 — and an
 * inline send would put a provider round trip inside the caller's response time, which is what
 * makes "this branch sent an email" measurable from outside.
 *
 * <p>Which leaves the log as the only trace a dropped email leaves, so what it says is the third
 * thing asserted here: the right marker, enough to act on, and nothing about the person.
 */
class EmailDispatcherTest {

  private static final String RECIPIENT = "person@example.com";
  private static final String REFERENCE = "3f1b8c9e-0000-4444-8888-aaaabbbbcccc";
  private static final String SUBJECT = "a subject";
  private static final String BODY = "a body";
  private static final int BUDGET_LIMIT = 80;

  // The markers EmailDispatcher logs each failure under, written out here as well as there. They
  // are an operational contract rather than an implementation detail — an alert or a saved search
  // matches these exact strings — so sharing a constant with the dispatcher would defeat the
  // point: the test has to fail if a marker is ever renamed.
  private static final String SEND_FAILED_MARKER = "EMAIL_SEND_FAILED";
  private static final String BUDGET_EXHAUSTED_MARKER = "EMAIL_BUDGET_EXHAUSTED";
  private static final String QUEUE_FULL_MARKER = "EMAIL_QUEUE_FULL";

  // What a crafted value would smuggle in: a line break followed by something shaped like a log
  // line of its own, so that one entry reads as two.
  private static final String FORGED_LINE = "ERROR forged log line";

  private static final EmailMessage MESSAGE = new EmailMessage(RECIPIENT, SUBJECT, BODY, REFERENCE);

  private static final EmailSender PROVIDER_REFUSES =
      message -> {
        throw new IllegalStateException("provider said no");
      };
  private static final EmailSender BUDGET_IS_EXHAUSTED =
      message -> {
        throw EmailBudgetExhaustedException.forExhaustedBudget(BUDGET_LIMIT, Duration.ofDays(1));
      };
  private static final Executor REJECTS_EVERY_SUBMISSION =
      task -> {
        throw new RejectedExecutionException("queue full");
      };

  private final List<EmailMessage> sent = new ArrayList<>();
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(EmailDispatcher.class);
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(captured);
    captured.stop();
  }

  // Every way a send can fail, from the caller's point of view: identical, and silent. The three
  // are told apart only in the log, which is the whole reason each has its own marker.
  @ParameterizedTest(name = "{0} never reaches the caller")
  @MethodSource("failures")
  void noFailureModeEverReachesTheCaller(String description, EmailSender sender) {
    EmailDispatcher dispatcher = new EmailDispatcher(sender, Runnable::run);

    assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of("a provider refusal", PROVIDER_REFUSES),
        Arguments.of("an exhausted budget", BUDGET_IS_EXHAUSTED));
  }

  static Stream<Arguments> failuresAndTheirMarkers() {
    return Stream.of(
        Arguments.of("a provider refusal", PROVIDER_REFUSES, SEND_FAILED_MARKER),
        Arguments.of("an exhausted budget", BUDGET_IS_EXHAUSTED, BUDGET_EXHAUSTED_MARKER));
  }

  // The third failure mode, which happens before the sender is ever reached: a full queue rejects
  // the submission itself. It must be as silent as the other two, and must send nothing.
  @Test
  void aFullDispatchQueueIsSwallowedAndNothingIsSent() {
    EmailDispatcher dispatcher = new EmailDispatcher(sent::add, REJECTS_EVERY_SUBMISSION);

    assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();

    assertThat(sent).isEmpty();
  }

  // Holding the submitted task unrun is what shows the work was genuinely handed off rather than
  // merely wrapped: if dispatch sent inline, the message would already be there.
  @Test
  void sendingIsHandedToTheExecutorRatherThanDoneOnTheCallingThread() {
    List<Runnable> submitted = new ArrayList<>();
    EmailDispatcher dispatcher = new EmailDispatcher(sent::add, submitted::add);

    dispatcher.dispatch(MESSAGE);

    assertThat(sent).isEmpty();
    assertThat(submitted).hasSize(1);

    submitted.getFirst().run();
    assertThat(sent).containsExactly(MESSAGE);
  }

  // The failure log is the only trace a dropped email leaves, and it is read by whoever is on
  // call — so it has to be enough to act on without being a place a person's address accumulates.
  // The reference resolves to an account for anyone with database access, and to nothing for
  // anyone without it. The body is withheld for a sharper reason than the address: it carries the
  // raw verification token, so logging it would hand over the credential itself.
  @ParameterizedTest(name = "{0} is logged against the reference, not the recipient")
  @MethodSource("failures")
  void aFailedSendLogsTheReferenceAndNeverTheRecipient(String description, EmailSender sender) {
    new EmailDispatcher(sender, Runnable::run).dispatch(MESSAGE);

    assertThat(captured.list).hasSize(1);
    String line = onlyLoggedLine();
    assertThat(line).contains(REFERENCE).doesNotContain(RECIPIENT).doesNotContain(BODY);
  }

  @Test
  void aRejectedSubmissionIsLoggedAgainstTheReferenceToo() {
    new EmailDispatcher(sent::add, REJECTS_EVERY_SUBMISSION).dispatch(MESSAGE);

    assertThat(captured.list).hasSize(1);
    String line = onlyLoggedLine();
    assertThat(line).contains(REFERENCE).doesNotContain(RECIPIENT).doesNotContain(BODY);
  }

  // Each failure needs its own marker because the response to each differs: an exhausted budget is
  // this service stopping itself, a provider refusal is something outside it breaking, and a full
  // queue means the message never reached the sender at all. The strings are matched by alerts and
  // saved searches, so they are contract — renaming one silently is what this asserts against.
  @ParameterizedTest(name = "{0} is logged under {2}")
  @MethodSource("failuresAndTheirMarkers")
  void eachFailureIsLoggedUnderItsOwnMarker(String description, EmailSender sender, String marker) {
    new EmailDispatcher(sender, Runnable::run).dispatch(MESSAGE);

    assertThat(onlyLoggedLine()).contains(marker);
  }

  @Test
  void aRejectedSubmissionIsLoggedUnderTheQueueFullMarker() {
    new EmailDispatcher(sent::add, REJECTS_EVERY_SUBMISSION).dispatch(MESSAGE);

    assertThat(onlyLoggedLine()).contains(QUEUE_FULL_MARKER);
  }

  // Both interpolated values reach this package from a calling domain, and either could carry a
  // value that started life as request input. A line break in one would end the log entry early
  // and let whatever followed be read as a separate entry of the attacker's choosing — a forged
  // ERROR, say, or a fabricated audit line.
  //
  // This is also the evidence for the CRLF_INJECTION_LOGS suppression on
  // EmailDispatcher#logFailure.
  // That suppression asserts the values are sanitized and that FindSecBugs simply loses the
  // sanitizer across the call; nothing in the analyser can confirm it, so the claim is only as good
  // as a test that a crafted value really does come out folded. Folded, not dropped: the text is
  // still there to read, which is what keeps the log useful.
  @Test
  void aFailureLogFoldsNewlinesInTheReferenceAndSubjectOntoOneLine() {
    EmailMessage crafted =
        new EmailMessage(
            RECIPIENT, SUBJECT + "\r\n" + FORGED_LINE, BODY, REFERENCE + "\r\n" + FORGED_LINE);

    new EmailDispatcher(PROVIDER_REFUSES, Runnable::run).dispatch(crafted);

    String line = onlyLoggedLine();
    assertThat(line).doesNotContain("\n").doesNotContain("\r");
    assertThat(line).contains(REFERENCE).contains(SUBJECT).contains(FORGED_LINE);
  }

  private String onlyLoggedLine() {
    assertThat(captured.list).hasSize(1);
    return captured.list.getFirst().getFormattedMessage();
  }
}
