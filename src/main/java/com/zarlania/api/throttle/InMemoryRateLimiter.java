package com.zarlania.api.throttle;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fixed-window limiter backed by a {@link ConcurrentHashMap}, one entry per key for the lifetime of
 * its window. Left alone, that map would grow for the life of the process — every distinct key
 * (endpoint + client IP) that has ever made a request stays resident, and an instance this service
 * runs on has a few hundred MB in total. {@link #evictExpiredWindows()} sweeps it clean on a
 * schedule instead of on every {@link #tryConsume} call: a full-map scan per request would trade
 * the leak for a latency hit on the auth hot path, whereas one scan per window is negligible.
 *
 * <p>Requires {@code @EnableScheduling} on the application, added for this in {@code
 * ZarlaniaApiApplication}.
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

  /**
   * Gauge reporting {@link #trackedKeyCount()}. How much memory the limiter is holding is not
   * derivable from anything else the service publishes, and the map growing without bound is the
   * failure mode this class is built around — so it is measured rather than assumed.
   */
  public static final String TRACKED_KEYS_METRIC = "zarlania.throttle.tracked.keys";

  private final ConcurrentHashMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();
  private final ThrottleProperties properties;
  private final Clock clock;

  /**
   * @param meterRegistry where the tracked-key gauge is registered. The gauge holds a reference to
   *     this limiter, which is a singleton, so it stays readable for the life of the application.
   */
  public InMemoryRateLimiter(
      ThrottleProperties properties, Clock clock, MeterRegistry meterRegistry) {
    this.properties = properties;
    this.clock = clock;
    Gauge.builder(TRACKED_KEYS_METRIC, this, InMemoryRateLimiter::trackedKeyCount)
        .description("Rate-limit windows currently held in memory")
        .register(meterRegistry);
  }

  @Override
  public ThrottleDecision tryConsume(String key, int limit) {
    return tryConsume(key, limit, properties.window());
  }

  @Override
  public ThrottleDecision tryConsume(String key, int limit, Duration windowLength) {
    Instant now = clock.instant();
    RateLimitWindow window =
        windows.compute(
            key,
            (k, existing) ->
                existing == null || existing.hasPassed(now)
                    ? new RateLimitWindow(now, windowLength, new AtomicInteger())
                    : existing);
    // incrementAndGet() runs outside compute() deliberately. compute()'s per-key lock only
    // guarantees a single RateLimitWindow instance is published for this key; it says nothing about
    // ordering against evictExpiredWindows() removing that same entry once its window has
    // passed. The counter itself is an AtomicInteger, so the increment can never be lost or
    // double-counted for callers racing on the *same* live window — the only edge case is a
    // request landing in the gap between compute() returning a RateLimitWindow and the sweep
    // dropping it, which discounts at most one increment against a key nobody will read again.
    // That is an acceptable approximation for a limiter, not a hole a client could exploit to
    // bypass the limit.
    if (window.count().incrementAndGet() <= limit) {
      return ThrottleDecision.permitted();
    }
    return ThrottleDecision.refused(window.remainingAt(now));
  }

  /**
   * How many windows are currently held in memory, as published by {@link #TRACKED_KEYS_METRIC}.
   *
   * <p>Deliberately not on {@link RateLimiter}: it describes how this implementation stores its
   * state, and a Redis-backed one would have nothing to report here. A caller deciding whether to
   * admit a request must never see it.
   *
   * <p>{@code final} because the constructor registers a gauge that calls it: an override running
   * against a half-built subclass is the hazard, and sealing the method removes it.
   */
  public final int trackedKeyCount() {
    return windows.size();
  }

  @Scheduled(fixedDelayString = "${zarlania.throttle.window}")
  void evictExpiredWindows() {
    Instant now = clock.instant();
    windows.entrySet().removeIf(entry -> entry.getValue().hasPassed(now));
  }
}
