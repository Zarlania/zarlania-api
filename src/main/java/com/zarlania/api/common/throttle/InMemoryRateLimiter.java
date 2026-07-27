package com.zarlania.api.common.throttle;

import java.time.Clock;
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

  private record Window(Instant start, AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final ThrottleProperties properties;
  private final Clock clock;

  @Override
  public boolean tryConsume(String key, int limit) {
    Instant now = clock.instant();
    Window window =
        windows.compute(
            key,
            (k, existing) ->
                existing == null || now.isAfter(existing.start().plus(properties.window()))
                    ? new Window(now, new AtomicInteger())
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
    return window.count().incrementAndGet() <= limit;
  }

  @Scheduled(fixedDelayString = "${zarlania.throttle.window}")
  void evictExpiredWindows() {
    Instant threshold = clock.instant().minus(properties.window());
    windows.entrySet().removeIf(entry -> entry.getValue().start().isBefore(threshold));
  }

  // Package-private: exists only for InMemoryRateLimiterTest to observe that eviction actually
  // shrinks the map. Not part of the RateLimiter contract, so it cannot leak into AuthController
  // or into a future Redis adapter's surface.
  int trackedKeyCount() {
    return windows.size();
  }
}
