package com.zarlania.api.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.common.throttle.InMemoryRateLimiter;
import com.zarlania.api.common.throttle.ThrottleProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A real {@link InMemoryRateLimiter} rather than a mock: the point of the budget is that the window
 * really is the configured period, and a stubbed limiter would assert nothing about that.
 */
class BudgetedEmailSenderTest {

  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  private static final Duration BUDGET_WINDOW = Duration.ofDays(1);
  private static final int BUDGET_LIMIT = 2;

  private final List<EmailMessage> delivered = new ArrayList<>();

  @Test
  void sendsThroughToTheDelegateWhileTheBudgetHasRoom() {
    BudgetedEmailSender sender = sender(NOW);

    sender.send(message("first@example.com"));
    sender.send(message("second@example.com"));

    assertThat(delivered).hasSize(2);
  }

  // Throwing rather than quietly dropping is the point: the caller logs it at error, so an
  // exhausted budget is visible instead of turning into verification emails nobody ever receives.
  @Test
  void refusesAndNeverReachesTheDelegateOnceTheBudgetIsSpent() {
    BudgetedEmailSender sender = sender(NOW);
    sender.send(message("first@example.com"));
    sender.send(message("second@example.com"));

    assertThatThrownBy(() -> sender.send(message("third@example.com")))
        .isInstanceOf(EmailBudgetExhaustedException.class);

    assertThat(delivered).hasSize(2);
  }

  // The window is the configured period, not the request-throttling one — a budget that reset
  // every minute would cap the rate while leaving the day's total unbounded.
  @Test
  void theBudgetRefillsOnlyAfterTheConfiguredWindowHasPassed() {
    MutableClock clock = new MutableClock(NOW);
    BudgetedEmailSender sender = sender(clock);
    sender.send(message("first@example.com"));
    sender.send(message("second@example.com"));

    clock.advance(Duration.ofHours(23));
    assertThatThrownBy(() -> sender.send(message("too-soon@example.com")))
        .isInstanceOf(EmailBudgetExhaustedException.class);

    clock.advance(Duration.ofHours(2));
    sender.send(message("next-day@example.com"));

    assertThat(delivered).hasSize(3);
  }

  private BudgetedEmailSender sender(Instant fixedAt) {
    return sender(Clock.fixed(fixedAt, ZoneOffset.UTC));
  }

  private BudgetedEmailSender sender(Clock clock) {
    ThrottleProperties properties =
        new ThrottleProperties(
            Duration.ofMinutes(1), 10, 5, 3, 30, 60, 10, 3, 3, BUDGET_LIMIT, BUDGET_WINDOW);
    return new BudgetedEmailSender(
        delivered::add, new InMemoryRateLimiter(properties, clock), BUDGET_LIMIT, BUDGET_WINDOW);
  }

  private static EmailMessage message(String to) {
    return new EmailMessage(to, "subject", "body");
  }

  /** Minimal advanceable clock; {@link Clock#fixed} cannot move and Thread.sleep is not a test. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration amount) {
      now = now.plus(amount);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
