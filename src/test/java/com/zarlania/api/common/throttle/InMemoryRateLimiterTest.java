package com.zarlania.api.common.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit-level: no Spring context, no database. {@link MutableClock} stands in for the injected
 * {@link Clock} bean so window expiry and eviction can be asserted deterministically instead of
 * sleeping in real time.
 */
class InMemoryRateLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final int SOME_OTHER_LIMIT = 100;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final InMemoryRateLimiter limiter =
      new InMemoryRateLimiter(
          new ThrottleProperties(
              WINDOW,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              SOME_OTHER_LIMIT,
              WINDOW),
          clock);

  @Test
  void allowsUpToTheLimitThenRejects() {
    assertThat(limiter.tryConsume("k", 3)).isTrue();
    assertThat(limiter.tryConsume("k", 3)).isTrue();
    assertThat(limiter.tryConsume("k", 3)).isTrue();

    assertThat(limiter.tryConsume("k", 3)).isFalse();
  }

  @Test
  void advancingPastTheWindowResetsTheCount() {
    assertThat(limiter.tryConsume("k", 1)).isTrue();
    assertThat(limiter.tryConsume("k", 1)).isFalse();

    clock.advance(WINDOW.plusSeconds(1));

    assertThat(limiter.tryConsume("k", 1)).isTrue();
  }

  @Test
  void differentKeysAreIndependent() {
    assertThat(limiter.tryConsume("a", 1)).isTrue();

    assertThat(limiter.tryConsume("b", 1)).isTrue();
  }

  @Test
  void evictExpiredWindowsRemovesEntriesOlderThanTheWindowSoMemoryDoesNotGrowForever() {
    limiter.tryConsume("stale", 5);
    limiter.tryConsume("fresh-at-sweep-time", 5);
    assertThat(limiter.trackedKeyCount()).isEqualTo(2);

    clock.advance(WINDOW.plusSeconds(1));
    limiter.evictExpiredWindows();

    assertThat(limiter.trackedKeyCount()).isZero();
  }

  @Test
  void evictExpiredWindowsLeavesEntriesStillInsideTheWindowAlone() {
    limiter.tryConsume("recent", 5);

    clock.advance(WINDOW.minusSeconds(1));
    limiter.evictExpiredWindows();

    assertThat(limiter.trackedKeyCount()).isEqualTo(1);
  }

  /** Settable {@link Clock} test double: real time never advances, only what the test asks for. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }
  }
}
