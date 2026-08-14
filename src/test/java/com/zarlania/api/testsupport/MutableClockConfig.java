package com.zarlania.api.testsupport;

import com.zarlania.api.time.ClockConfig;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@code Clock} with a {@link MutableClock} wherever a test
 * {@code @Import}s this class, so a Spring-backed test reads a time it states rather than the wall
 * clock.
 *
 * <p>Worth importing for any test whose subject involves a duration — a throttle window, a token
 * TTL, an eviction sweep. Frozen time removes the whole class of failure where a test passes except
 * when it happens to straddle a boundary, and advancing the clock replaces sleeping with an
 * instruction.
 *
 * <p>{@code @Primary} over {@link ClockConfig}'s bean rather than a profile, matching {@link
 * RecordingEmailSenderConfig}: the substitution is then visible in each test's import list instead
 * of hidden in whichever profile happened to be active. Declared as {@link MutableClock} rather
 * than {@code Clock} so a test that wants to advance time can inject it without a cast.
 *
 * <p>No reset between test methods, and none needed — {@link IntegrationTestBase} dirties the
 * context after each one, so every method is handed a clock built fresh at {@link #BASE_INSTANT}. A
 * method that advances time and then needs to be back at the baseline within that same method has
 * to arrange that itself, since {@link MutableClock} only moves forward.
 */
@TestConfiguration
public class MutableClockConfig {

  /**
   * Where test time starts: an arbitrary, microsecond-clean instant, well clear of any epoch or
   * boundary that could make an off-by-one look correct.
   *
   * <p>Microsecond-clean because {@link ClockConfig} truncates the real clock to the microsecond
   * resolution {@code timestamptz(6)} stores, and a test clock ticking finer than the schema would
   * make an instant compare equal in memory and unequal after a round trip.
   */
  public static final Instant BASE_INSTANT = Instant.parse("2025-06-15T12:00:00Z");

  /** The clock every component in the context under test reads, in place of the system clock. */
  @Bean
  @Primary
  public MutableClock mutableClock() {
    return new MutableClock(BASE_INSTANT);
  }
}
