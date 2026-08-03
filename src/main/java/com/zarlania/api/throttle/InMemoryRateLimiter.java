package com.zarlania.api.throttle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window limiter backed by a {@link ConcurrentHashMap}, one entry per key for the lifetime of
 * its window. Left alone, that map would grow for the life of the process — every distinct key
 * (endpoint + client IP) that has ever made a request stays resident, and the Render free tier's
 * entire budget is 512 MB. {@link #evictExpiredWindows()} sweeps it clean on a schedule instead of
 * on every {@link #tryConsume} call: a full-map scan per request would trade the leak for a latency
 * hit on the auth hot path, whereas one scan per window is negligible.
 *
 * <p>Requires {@code @EnableScheduling} on the application, added for this in {@code
 * ZarlaniaApiApplication}.
 */
@Component
@RequiredArgsConstructor
public class InMemoryRateLimiter implements RateLimiter {

  /**
   * Each entry carries its own length rather than reading the configured one: keys with different
   * periods share this map (the per-request buckets on a one-minute window, the global email budget
   * on a daily one), and eviction has to know which is which or it would drop a day-long window the
   * moment a minute had passed.
   */
  private record Window(Instant start, Duration length, AtomicInteger count) {

    boolean hasPassed(Instant now) {
      return now.isAfter(start.plus(length));
    }

    Duration remainingAt(Instant now) {
      return Duration.between(now, start.plus(length));
    }
  }

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final ThrottleProperties properties;
  private final Clock clock;

  @Override
  public ThrottleDecision tryConsume(String key, int limit) {
    return tryConsume(key, limit, properties.window());
  }

  @Override
  public ThrottleDecision tryConsume(String key, int limit, Duration windowLength) {
    Instant now = clock.instant();
    Window window =
        windows.compute(
            key,
            (k, existing) ->
                existing == null || existing.hasPassed(now)
                    ? new Window(now, windowLength, new AtomicInteger())
                    : existing);
    // incrementAndGet() runs outside compute() deliberately. compute()'s per-key lock only
    // guarantees a single Window instance is published for this key; it says nothing about
    // ordering against evictExpiredWindows() removing that same entry once its window has
    // passed. The counter itself is an AtomicInteger, so the increment can never be lost or
    // double-counted for callers racing on the *same* live window — the only edge case is a
    // request landing in the gap between compute() returning a Window and the sweep dropping
    // it, which discounts at most one increment against a key nobody will read again. That is
    // an acceptable approximation for a limiter, not a hole a client could exploit to bypass
    // the limit.
    if (window.count().incrementAndGet() <= limit) {
      return ThrottleDecision.permitted();
    }
    return ThrottleDecision.refused(window.remainingAt(now));
  }

  @Scheduled(fixedDelayString = "${zarlania.throttle.window}")
  void evictExpiredWindows() {
    Instant now = clock.instant();
    windows.entrySet().removeIf(entry -> entry.getValue().hasPassed(now));
  }

  /**
   * How many keys are currently tracked. Package-private: it exists only so a test can observe that
   * eviction actually shrinks the map, and keeping it off {@link RateLimiter} means it can leak
   * neither into a caller nor into a future Redis adapter's surface.
   */
  int trackedKeyCount() {
    return windows.size();
  }
}
