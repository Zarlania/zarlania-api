package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-level: no Spring context, no database. {@link MutableClock} stands in for the injected
 * {@link Clock} bean so window expiry and eviction can be asserted deterministically instead of
 * sleeping in real time.
 */
class InMemoryRateLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int EMAIL_BUDGET_LIMIT = 100;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final InMemoryRateLimiter limiter =
      new InMemoryRateLimiter(
          new ThrottleProperties(WINDOW, Map.of(), EMAIL_BUDGET_LIMIT, WINDOW),
          clock,
          meterRegistry);

  @Test
  void allowsUpToTheLimitThenRejects() {
    assertThat(limiter.tryConsume("k", 3).allowed()).isTrue();
    assertThat(limiter.tryConsume("k", 3).allowed()).isTrue();
    assertThat(limiter.tryConsume("k", 3).allowed()).isTrue();

    assertThat(limiter.tryConsume("k", 3).allowed()).isFalse();
  }

  @Test
  void advancingPastTheWindowResetsTheCount() {
    assertThat(limiter.tryConsume("k", 1).allowed()).isTrue();
    assertThat(limiter.tryConsume("k", 1).allowed()).isFalse();

    clock.advance(WINDOW.plusSeconds(1));

    assertThat(limiter.tryConsume("k", 1).allowed()).isTrue();
  }

  @Test
  void differentKeysAreIndependent() {
    assertThat(limiter.tryConsume("a", 1).allowed()).isTrue();

    assertThat(limiter.tryConsume("b", 1).allowed()).isTrue();
  }

  @Test
  void aPermittedRequestReportsNoWaitAtAll() {
    assertThat(limiter.tryConsume("k", 1).retryAfter()).isZero();
  }

  @Test
  void aRefusedRequestReportsTheWholeWindowWhenItHasJustOpened() {
    limiter.tryConsume("k", 1);

    assertThat(limiter.tryConsume("k", 1).retryAfter()).isEqualTo(WINDOW);
  }

  // The wait shrinks as the window ages rather than restating the window length, which is the whole
  // point of returning it: a caller refused one second before the refill is told to wait one
  // second.
  @Test
  void aRefusedRequestReportsOnlyWhatIsLeftOfTheWindowItExhausted() {
    limiter.tryConsume("k", 1);
    clock.advance(WINDOW.minusSeconds(10));

    assertThat(limiter.tryConsume("k", 1).retryAfter()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void evictExpiredWindowsRemovesEntriesOlderThanTheWindowSoMemoryDoesNotGrowForever() {
    limiter.tryConsume("stale", 5);
    limiter.tryConsume("fresh-at-sweep-time", 5);
    assertThat(trackedKeysGauge()).isEqualTo(2);

    clock.advance(WINDOW.plusSeconds(1));
    limiter.evictExpiredWindows();

    assertThat(trackedKeysGauge()).isZero();
  }

  @Test
  void evictExpiredWindowsLeavesEntriesStillInsideTheWindowAlone() {
    limiter.tryConsume("recent", 5);

    clock.advance(WINDOW.minusSeconds(1));
    limiter.evictExpiredWindows();

    assertThat(trackedKeysGauge()).isEqualTo(1);
  }

  // Read through the registry rather than off the limiter, so these tests fail if the gauge stops
  // being published — the metric is how an operator sees this, and an unpublished one is the same
  // as no eviction monitoring at all.
  private double trackedKeysGauge() {
    return meterRegistry.get(InMemoryRateLimiter.TRACKED_KEYS_METRIC).gauge().value();
  }
}
