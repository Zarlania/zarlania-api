package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation and the copy this record does as it is bound, which is the only logic it holds.
 *
 * <p>The email budget is the whole service's ceiling on outbound mail, and both halves of it fail
 * silently when misconfigured — in opposite directions. A non-positive limit refuses every send, so
 * no address is ever verified and no account can log in. A non-positive window makes each send
 * start a fresh window, so the ceiling is never reached and the budget that exists to keep a free
 * provider tier from being drained simply is not there. Neither shows up until it matters.
 */
class ThrottlePropertiesTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final Duration BUDGET_WINDOW = Duration.ofDays(1);
  private static final int BUDGET_LIMIT = 80;
  private static final String LOGIN_ENDPOINT = "login";

  @Test
  void bindsWhenEveryPropertyIsUsable() {
    ThrottleProperties properties =
        new ThrottleProperties(
            WINDOW,
            Map.of(LOGIN_ENDPOINT, new EndpointLimits(10, 10)),
            BUDGET_LIMIT,
            BUDGET_WINDOW);

    assertThat(properties.window()).isEqualTo(WINDOW);
    assertThat(properties.emailBudgetLimit()).isEqualTo(BUDGET_LIMIT);
    assertThat(properties.emailBudgetWindow()).isEqualTo(BUDGET_WINDOW);
    assertThat(properties.limitsFor(LOGIN_ENDPOINT)).isEqualTo(new EndpointLimits(10, 10));
  }

  // A throttle whose limits could still be changed after startup by whatever handed the map in
  // would be no throttle at all, so the copy matters as much as the validation around it.
  @Test
  void copiesTheEndpointsMapSoLaterChangesToTheSourceCannotReachIt() {
    Map<String, EndpointLimits> source = new HashMap<>();
    source.put(LOGIN_ENDPOINT, new EndpointLimits(10, 10));
    ThrottleProperties properties =
        new ThrottleProperties(WINDOW, source, BUDGET_LIMIT, BUDGET_WINDOW);

    source.put(LOGIN_ENDPOINT, new EndpointLimits(9999, 9999));
    source.put("registerlater", new EndpointLimits(1, 1));

    assertThat(properties.limitsFor(LOGIN_ENDPOINT)).isEqualTo(new EndpointLimits(10, 10));
    assertThat(properties.endpoints()).hasSize(1);
  }

  @Test
  void rejectsAMissingEndpointsBlockNamingTheProperty() {
    assertThatThrownBy(() -> new ThrottleProperties(WINDOW, null, BUDGET_LIMIT, BUDGET_WINDOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("zarlania.throttle.endpoints");
  }

  // Not positive means tryConsume refuses the very first send, so every verification email is
  // dropped and no account registered after that point can ever be used.
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsANonPositiveEmailBudgetLimitNamingTheProperty(int limit) {
    assertThatThrownBy(() -> new ThrottleProperties(WINDOW, Map.of(), limit, BUDGET_WINDOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.throttle.email-budget-limit")
        .hasMessageContaining(String.valueOf(limit));
  }

  @Test
  void rejectsAMissingEmailBudgetWindowNamingTheProperty() {
    assertThatThrownBy(() -> new ThrottleProperties(WINDOW, Map.of(), BUDGET_LIMIT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("zarlania.throttle.email-budget-window");
  }

  // The dangerous direction. A window that has already elapsed by the time the next send arrives
  // starts a fresh one every time, so the count never reaches the limit and the budget silently
  // stops existing — no exception, no log, nothing until the provider's quota is gone.
  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-24H"})
  void rejectsANonPositiveEmailBudgetWindowNamingTheProperty(String window) {
    Duration nonPositive = Duration.parse(window);

    assertThatThrownBy(() -> new ThrottleProperties(WINDOW, Map.of(), BUDGET_LIMIT, nonPositive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.throttle.email-budget-window")
        .hasMessageContaining(nonPositive.toString());
  }
}
