package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zarlania.api.email.exceptions.EmailBudgetExhaustedException;
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
 */
class EmailDispatcherTest {

  private static final String RECIPIENT = "person@example.com";
  private static final String REFERENCE = "3f1b8c9e-0000-4444-8888-aaaabbbbcccc";

  private static final EmailMessage MESSAGE =
      new EmailMessage(RECIPIENT, "a subject", "a body", REFERENCE);

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
  // are
  // told apart only in the log, which is the whole reason each has its own marker.
  @ParameterizedTest(name = "{0} never reaches the caller")
  @MethodSource("failures")
  void noFailureModeEverReachesTheCaller(String description, EmailSender sender) {
    EmailDispatcher dispatcher = new EmailDispatcher(sender, Runnable::run);

    assertThatCode(() -> dispatcher.dispatch(MESSAGE)).doesNotThrowAnyException();
  }

  static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(
            "a provider refusal",
            (EmailSender)
                message -> {
                  throw new IllegalStateException("provider said no");
                }),
        Arguments.of(
            "an exhausted budget",
            (EmailSender)
                message -> {
                  throw EmailBudgetExhaustedException.forExhaustedBudget(
                      80, java.time.Duration.ofDays(1));
                }));
  }

  // The third failure mode, which happens before the sender is ever reached: a full queue rejects
  // the submission itself. It must be as silent as the other two, and must send nothing.
  @Test
  void aFullDispatchQueueIsSwallowedAndNothingIsSent() {
    Executor rejecting =
        task -> {
          throw new RejectedExecutionException("queue full");
        };
    EmailDispatcher dispatcher = new EmailDispatcher(sent::add, rejecting);

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
  // anyone without it.
  @ParameterizedTest(name = "{0} is logged against the reference, not the recipient")
  @MethodSource("failures")
  void aFailedSendLogsTheReferenceAndNeverTheRecipient(String description, EmailSender sender) {
    new EmailDispatcher(sender, Runnable::run).dispatch(MESSAGE);

    assertThat(captured.list).hasSize(1);
    String line = captured.list.getFirst().getFormattedMessage();
    assertThat(line).contains(REFERENCE).doesNotContain(RECIPIENT);
  }

  @Test
  void aRejectedSubmissionIsLoggedAgainstTheReferenceToo() {
    Executor rejecting =
        task -> {
          throw new RejectedExecutionException("queue full");
        };

    new EmailDispatcher(sent::add, rejecting).dispatch(MESSAGE);

    assertThat(captured.list).hasSize(1);
    String line = captured.list.getFirst().getFormattedMessage();
    assertThat(line).contains(REFERENCE).doesNotContain(RECIPIENT);
  }
}
