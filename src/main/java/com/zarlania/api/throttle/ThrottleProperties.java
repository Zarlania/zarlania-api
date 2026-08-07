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
   * @throws NullPointerException if no {@code endpoints} block is configured — failing at startup
   *     is the right answer, since every {@link Throttled} endpoint would otherwise run unlimited
   */
  public ThrottleProperties {
    endpoints = Map.copyOf(Objects.requireNonNull(endpoints, "zarlania.throttle.endpoints"));
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
