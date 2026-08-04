package com.zarlania.api.throttle;

import java.util.Optional;

/**
 * One endpoint's two throttle limits, both counted over {@link ThrottleProperties#window()}. Bound
 * from a {@code zarlania.throttle.endpoints} entry, keyed by the name in {@link
 * Throttled#endpoint()}.
 *
 * @param limit requests allowed per client IP
 * @param accountLimit requests allowed per account named in the request, across every IP; absent
 *     for endpoints that name no account, such as refresh (which carries only a cookie) and the
 *     CSRF token endpoint (which carries nothing)
 */
public record EndpointLimits(int limit, Integer accountLimit) {

  /** The per-account limit, empty when this endpoint has no account bucket. */
  public Optional<Integer> accountLimitIfPresent() {
    return Optional.ofNullable(accountLimit);
  }
}
