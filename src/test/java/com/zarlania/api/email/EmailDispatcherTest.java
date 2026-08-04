package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zarlania.api.email.exceptions.EmailBudgetExhaustedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  private static final EmailMessage MESSAGE =
      new EmailMessage("person@example.com", "a subject", "a body");

  private final List<EmailMessage> sent = new ArrayList<>();

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
                  throw new EmailBudgetExhaustedException("budget spent");
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
}
