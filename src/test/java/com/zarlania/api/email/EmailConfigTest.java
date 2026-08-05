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

  @Test
  void whicheverAdapterIsChosenIsWrappedInTheServiceWideBudget() {
    ThrottleProperties properties =
        new ThrottleProperties(Duration.ofMinutes(1), Map.of(), BUDGET_LIMIT, Duration.ofDays(1));

    EmailSender sender =
        new EmailConfig()
            .emailSender(
                API_KEY,
                FROM_ADDRESS,
                BASE_URL,
                new MockEnvironment(),
                new InMemoryRateLimiter(properties, Clock.systemUTC(), new SimpleMeterRegistry()),
                properties);

    assertThat(sender).isInstanceOf(BudgetedEmailSender.class);
  }

  // The pool has to reject rather than queue without limit: an unbounded queue turns a provider
  // outage into an out-of-memory kill on an instance with a few hundred MB to spend.
  @Test
  void theDispatchPoolIsBoundedSoAProviderOutageCannotExhaustMemory() {
    var executor = new EmailConfig().emailDispatchExecutor(1, 200);
    executor.afterPropertiesSet();

    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(200);
    assertThat(executor.getCorePoolSize()).isEqualTo(1);

    executor.shutdown();
  }
}
