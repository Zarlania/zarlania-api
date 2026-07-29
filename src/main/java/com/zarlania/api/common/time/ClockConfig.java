package com.zarlania.api.common.time;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  // Every timestamp column in this schema is timestamptz(6) — microsecond precision — so any
  // Instant this app hands out should already be microsecond-clean. Left untouched,
  // Clock.systemUTC() resolves to microseconds on macOS but nanoseconds on Linux: a service that
  // both persists an Instant and returns it to a caller (e.g. RefreshTokenService#startFamily)
  // would hand back a value with nanoseconds Postgres silently drops on write, so the in-memory
  // and persisted values would agree only by accident of platform. Truncating once here, at the
  // one place every Instant in the app is created, means no call site has to remember to do it.
  private static final Duration MICROSECOND_RESOLUTION = Duration.ofNanos(1_000);

  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), MICROSECOND_RESOLUTION);
  }
}
