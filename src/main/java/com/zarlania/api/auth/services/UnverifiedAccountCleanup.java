package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Purges registrations nobody ever verified. Past {@link AuthProperties#unverifiedAccountMaxAge()}
 * old, an abandoned signup still holds its email, username and organization name hostage forever —
 * all three are {@code citext NOT NULL UNIQUE} — so nobody else can register with them. Runs on
 * {@code zarlania.auth.cleanup-interval}, riding the {@code @EnableScheduling} already turned on
 * for {@code InMemoryRateLimiter}'s eviction sweep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnverifiedAccountCleanup {

  private final UserService userService;
  private final UnverifiedAccountPurger purger;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${zarlania.auth.cleanup-interval:PT1H}")
  public void purgeExpiredUnverifiedAccounts() {
    Instant cutoff = clock.instant().minus(authProperties.unverifiedAccountMaxAge());
    // DTOs from the users domain's own service, not User entities from its repository: an entity
    // never leaves the domain that owns it, and only the id is needed here anyway.
    for (UserDto user : userService.findUnverifiedOlderThan(cutoff)) {
      purgeSafely(user.id());
    }
  }

  // One bad row must not abort the sweep: every other expired account still needs purging on this
  // pass, and whichever user failed is simply picked up again on the next scheduled run. The catch
  // is deliberately broad because the whole point is resilience against whatever Postgres or
  // Hibernate throws, not one specific expected failure mode — there is no narrower common
  // supertype to catch instead.
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "userId is a java.util.UUID, not caller-supplied text — its toString() is always"
              + " lowercase hex digits and hyphens (RFC 4122), so there is no injectable"
              + " character to strip, unlike a user-supplied string such as an email address.")
  @SuppressWarnings("checkstyle:IllegalCatch")
  private void purgeSafely(UUID userId) {
    try {
      purger.purgeOneAccount(userId);
    } catch (RuntimeException e) {
      log.error("Failed to purge unverified account {}", userId, e);
    }
  }
}
