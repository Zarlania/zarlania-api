package com.zarlania.api.throttle;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fixed window of {@link InMemoryRateLimiter}'s counting, held per key.
 *
 * <p>Each window carries its own length rather than reading the configured one: keys with different
 * periods share the limiter's map (the per-request buckets on a one-minute window, the global email
 * budget on a daily one), and eviction has to know which is which or it would drop a day-long
 * window the moment a minute had passed.
 *
 * @param count mutable on purpose — callers racing on the same live window increment it directly,
 *     which is what keeps the increment off the map's per-key lock
 */
record RateLimitWindow(Instant start, Duration length, AtomicInteger count) {

  /**
   * Whether {@code now} has moved past this window's end, so the window may be replaced or swept.
   */
  boolean hasPassed(Instant now) {
    return now.isAfter(start.plus(length));
  }

  /** How long until this window ends, which is how long a refused caller must wait. */
  Duration remainingAt(Instant now) {
    return Duration.between(now, start.plus(length));
  }
}
