package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.throttle.InMemoryRateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which adapter the outbound channel is built from, and what it is wrapped in.
 *
 * <p>Both decisions are silent when wrong, which is why they are pinned here. A deployment that
 * quietly logged its verification emails instead of sending them would look healthy while stranding
 * every new account, and an adapter that escaped the budget decorator would send outside the
 * service-wide cap without anything reporting it.
 */
class EmailConfigTest {

  private static final String FROM_ADDRESS = "no-reply@zarlania.com";
  private static final String API_KEY = "re_test_key";
  private static final String PRODUCTION_PROFILE = "production";

  @Test
  void aBlankKeyOutsideProductionSelectsTheLoggingAdapter() {
    EmailConfig config = new EmailConfig();
    MockEnvironment environment = new MockEnvironment();

    EmailSender sender = config.provider("", FROM_ADDRESS, environment);

    assertThat(sender).isInstanceOf(LoggingEmailSender.class);
  }

  @Test
  void aBlankKeyInProductionFailsStartupRatherThanFallingBackToLogging() {
    EmailConfig config = new EmailConfig();
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(PRODUCTION_PROFILE);

    assertThatThrownBy(() -> config.provider("", FROM_ADDRESS, environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aConfiguredKeySelectsTheRealProvider() {
    EmailConfig config = new EmailConfig();
    MockEnvironment environment = new MockEnvironment();

    EmailSender sender = config.provider(API_KEY, FROM_ADDRESS, environment);

    assertThat(sender).isInstanceOf(ResendEmailSender.class);
  }

  // Whichever provider is chosen, the bean the rest of the application injects has to be the
  // budgeted one — otherwise a caller added later sends outside the service-wide cap.
  @Test
  void whicheverAdapterIsChosenIsWrappedInTheServiceWideBudget() {
    EmailConfig config = new EmailConfig();
    ThrottleProperties properties =
        new ThrottleProperties(Duration.ofMinutes(1), Map.of(), 80, Duration.ofDays(1));

    EmailSender sender =
        config.emailSender(
            API_KEY,
            FROM_ADDRESS,
            new MockEnvironment(),
            new InMemoryRateLimiter(properties, Clock.systemUTC()),
            properties);

    assertThat(sender).isInstanceOf(BudgetedEmailSender.class);
  }
}
