package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.testsupport.MutableClock;
import com.zarlania.api.throttle.InMemoryRateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        new ThrottleProperties(Duration.ofMinutes(1), Map.of(), BUDGET_LIMIT, BUDGET_WINDOW);
    return new BudgetedEmailSender(
        delivered::add, new InMemoryRateLimiter(properties, clock), BUDGET_LIMIT, BUDGET_WINDOW);
  }

  private static EmailMessage message(String to) {
    return new EmailMessage(to, "subject", "body");
  }
}
