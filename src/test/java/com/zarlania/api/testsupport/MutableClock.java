package com.zarlania.api.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Settable {@link Clock} test double: real time never advances, only what a test asks for. Standing
 * in for the injected {@code Clock} bean is what lets window expiry, token TTLs and eviction be
 * asserted deterministically instead of by sleeping.
 *
 * <p>Shared rather than nested in each test, so that a change to how time is faked happens once.
 */
public final class MutableClock extends Clock {

  private Instant instant;

  public MutableClock(Instant instant) {
    this.instant = instant;
  }

  /** Moves this clock forward. Nothing else observes the change until it next reads the clock. */
  public void advance(Duration amount) {
    instant = instant.plus(amount);
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
    throw new UnsupportedOperationException("Test clock is fixed to UTC");
  }
}
