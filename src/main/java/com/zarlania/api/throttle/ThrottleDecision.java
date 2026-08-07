package com.zarlania.api.throttle;

import java.time.Duration;

/**
 * The outcome of one {@link RateLimiter#tryConsume} call: whether the request may proceed, and if
 * not, how long the caller must wait for the window holding it back to refill.
 *
 * <p>The wait is returned alongside the verdict rather than through a second lookup so that both
 * come from the same view of the window. A separate {@code retryAfterFor(key)} call could observe a
 * window that has since been replaced or evicted, and would then advertise a wait that never
 * matched the rejection it described.
 */
public record ThrottleDecision(boolean allowed, Duration retryAfter) {

  private static final ThrottleDecision PERMITTED = new ThrottleDecision(true, Duration.ZERO);

  /** The request fits within its limit. {@link #retryAfter()} is {@link Duration#ZERO}. */
  public static ThrottleDecision permitted() {
    return PERMITTED;
  }

  /**
   * The request exceeds its limit and must not proceed.
   *
   * @param retryAfter how long until the window it exhausted refills
   */
  public static ThrottleDecision refused(Duration retryAfter) {
    return new ThrottleDecision(false, retryAfter);
  }
}
