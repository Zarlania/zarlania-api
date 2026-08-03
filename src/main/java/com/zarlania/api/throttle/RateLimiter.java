package com.zarlania.api.throttle;

import java.time.Duration;

/**
 * Fixed-window request throttle keyed by an arbitrary string — an endpoint name plus client
 * identity, in practice. {@link InMemoryRateLimiter} is the only implementation today; the
 * interface stays this narrow so a future Redis-backed implementation — needed once the API runs on
 * more than one instance, since in-memory counters neither survive a restart nor coordinate across
 * processes — can drop in without any caller changing.
 */
public interface RateLimiter {

  /**
   * Consumes one unit against {@code key} within the configured {@code zarlania.throttle.window}.
   */
  boolean tryConsume(String key, int limit);

  /**
   * Consumes one unit against {@code key} within an explicit window. Exists for budgets whose
   * period is not the request-throttling window: the global outbound email cap ({@code
   * email.BudgetedEmailSender}) is a daily allowance, and expressing it in one-minute windows would
   * cap the rate while leaving the day's total unbounded.
   */
  boolean tryConsume(String key, int limit, Duration window);
}
