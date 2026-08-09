package com.zarlania.api.throttle;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.throttle} configuration block; see {@code application.yml}.
 *
 * <p>Limits are a map keyed by endpoint name rather than one record component per endpoint, so that
 * throttling a new endpoint is a {@link Throttled} annotation plus a configuration entry and never
 * a change to this type. The name in the annotation is the key here.
 */
@ConfigurationProperties(prefix = "zarlania.throttle")
public record ThrottleProperties(
    Duration window,
    Map<String, EndpointLimits> endpoints,
    int emailBudgetLimit,
    Duration emailBudgetWindow) {

  /**
   * Copies the bound map, so the limits cannot be altered after startup by anything holding a
   * reference to what the binder passed in. A throttle whose limits could be changed at runtime by
   * an unrelated caller would be no throttle at all.
   *
   * <p>Also rejects durations and limits the service cannot throttle on, none of which announce
   * themselves. A non-positive window is the widest of them: {@link RateLimitWindow#hasPassed} asks
   * whether now is after the window's start plus its length, so one of zero or less has always
   * elapsed by the next request. Each request then starts a fresh window, no count ever climbs, and
   * every per-IP and per-account limit stops applying at once — login brute-forcing and
   * registration spam both run unbounded, with nothing refused and nothing logged to say so. The
   * same reasoning makes {@code emailBudgetWindow} the dangerous half of the email budget, where
   * what goes unenforced is the ceiling keeping a free provider tier from being drained.
   *
   * <p>A non-positive {@code emailBudgetLimit} fails the opposite way, refusing the very first
   * send: no verification email leaves the service, and no account registered afterwards can ever
   * be used.
   *
   * <p>Every message names the property as it is written in configuration, not the record component
   * — whoever reads one is looking at {@code application.yml} or an environment variable.
   *
   * @throws NullPointerException if {@code window} or {@code emailBudgetWindow} is not configured,
   *     or if no {@code endpoints} block is — failing at startup is the right answer for the last,
   *     since every {@link Throttled} endpoint would otherwise run unlimited
   * @throws IllegalArgumentException if {@code window} or {@code emailBudgetWindow} is not
   *     positive, or if {@code emailBudgetLimit} is not at least one
   */
  public ThrottleProperties {
    Objects.requireNonNull(window, "zarlania.throttle.window");
    if (!window.isPositive()) {
      throw new IllegalArgumentException(
          "zarlania.throttle.window must be positive, but was " + window);
    }
    endpoints = Map.copyOf(Objects.requireNonNull(endpoints, "zarlania.throttle.endpoints"));
    if (emailBudgetLimit < 1) {
      throw new IllegalArgumentException(
          "zarlania.throttle.email-budget-limit must be at least 1, but was " + emailBudgetLimit);
    }
    Objects.requireNonNull(emailBudgetWindow, "zarlania.throttle.email-budget-window");
    if (!emailBudgetWindow.isPositive()) {
      throw new IllegalArgumentException(
          "zarlania.throttle.email-budget-window must be positive, but was " + emailBudgetWindow);
    }
  }

  /**
   * Looks up one endpoint's limits.
   *
   * @param endpoint the name used in {@link Throttled#endpoint()}
   * @throws IllegalStateException if nothing is configured under that name, since an endpoint
   *     annotated as throttled but absent from configuration would otherwise silently run unlimited
   */
  public EndpointLimits limitsFor(String endpoint) {
    EndpointLimits limits = endpoints.get(endpoint);
    if (limits == null) {
      throw new IllegalStateException(
          "No zarlania.throttle.endpoints entry for throttled endpoint: " + endpoint);
    }
    return limits;
  }
}
