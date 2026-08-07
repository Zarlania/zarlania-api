package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.throttle.InMemoryRateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * What the rest of the application gets when it injects an {@link EmailSender}.
 *
 * <p>Which adapter is chosen is {@code EmailSenderFactoryTest}'s subject. What matters here is the
 * wrapping: an adapter that escaped the budget decorator would send outside the service-wide cap
 * without anything reporting it.
 */
class EmailConfigTest {

  private static final String FROM_ADDRESS = "no-reply@zarlania.com";
  private static final String BASE_URL = "https://api.resend.com";
  private static final String API_KEY = "re_test_key";
  private static final int BUDGET_LIMIT = 80;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int DISPATCH_THREADS = 1;
  private static final int DISPATCH_QUEUE_CAPACITY = 200;

  @Test
  void whicheverAdapterIsChosenIsWrappedInTheServiceWideBudget() {
    ThrottleProperties properties =
        new ThrottleProperties(Duration.ofMinutes(1), Map.of(), BUDGET_LIMIT, Duration.ofDays(1));

    EmailSender sender =
        new EmailConfig()
            .emailSender(
                emailProperties(),
                new MockEnvironment(),
                new InMemoryRateLimiter(properties, Clock.systemUTC(), new SimpleMeterRegistry()),
                properties);

    assertThat(sender).isInstanceOf(BudgetedEmailSender.class);
  }

  // The pool has to reject rather than queue without limit: an unbounded queue turns a provider
  // outage into an out-of-memory kill on an instance with a few hundred MB to spend.
  @Test
  void theDispatchPoolIsBoundedSoAProviderOutageCannotExhaustMemory() {
    var executor = new EmailConfig().emailDispatchExecutor(emailProperties());
    executor.afterPropertiesSet();

    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
        .isEqualTo(DISPATCH_QUEUE_CAPACITY);
    assertThat(executor.getCorePoolSize()).isEqualTo(DISPATCH_THREADS);

    executor.shutdown();
  }

  private static EmailProperties emailProperties() {
    return new EmailProperties(
        FROM_ADDRESS,
        API_KEY,
        BASE_URL,
        TIMEOUT,
        TIMEOUT,
        DISPATCH_THREADS,
        DISPATCH_QUEUE_CAPACITY);
  }
}
