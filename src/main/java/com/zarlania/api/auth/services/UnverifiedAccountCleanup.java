package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.exceptions.AccountVerifiedDuringPurgeException;
import com.zarlania.api.users.dtos.User;
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
  private final UnverifiedAccountPurger unverifiedAccountPurger;
  private final AuthProperties authProperties;
  private final Clock clock;

  /**
   * Purges accounts that never verified their address within the configured window, along with
   * everything hanging off them.
   *
   * <p>Each account is purged in its own transaction, so one failure abandons that account only and
   * the sweep carries on. Listing and purging are separate transactions, so every purge re-checks
   * that the account is still unverified before removing it.
   */
  @Scheduled(fixedDelayString = "${zarlania.auth.cleanup-interval:PT1H}")
  public void purgeExpiredUnverifiedAccounts() {
    Instant cutoff = clock.instant().minus(authProperties.unverifiedAccountMaxAge());
    // DTOs from the users domain's own service, not User entities from its repository: an entity
    // never leaves the domain that owns it, and only the id is needed here anyway.
    for (User user : userService.findUnverifiedOlderThan(cutoff)) {
      purgeSafely(user.id());
    }
  }

  /**
   * Purges one account, absorbing whatever it fails with.
   *
   * <p>One bad row must not abort the sweep: every other expired account still needs purging on
   * this pass, and whichever user failed is simply picked up again on the next scheduled run. The
   * catch is deliberately broad because the whole point is resilience against whatever Postgres or
   * Hibernate throws, not one specific expected failure mode — there is no narrower common
   * supertype to catch instead.
   *
   * @param userId the account to purge; logged, so it is the only thing about the account this
   *     method may emit — an email or username here would put a person's details in the logs
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "userId is a java.util.UUID, not caller-supplied text — its toString() is always"
              + " lowercase hex digits and hyphens (RFC 4122), so there is no injectable"
              + " character to strip, unlike a user-supplied string such as an email address.")
  @SuppressWarnings("checkstyle:IllegalCatch")
  private void purgeSafely(UUID userId) {
    try {
      unverifiedAccountPurger.purgeOneAccount(userId);
    } catch (AccountVerifiedDuringPurgeException exception) {
      // Not a failure: the account was verified between this sweep listing it and the purge
      // reaching it, the purge rolled itself back, and the account is intact. Logged at debug
      // rather than error so a routine, self-correcting race never pages anyone — but logged, so
      // that a sudden run of these is still visible if the cutoff is ever misconfigured.
      log.debug("Skipped purging {}: it was verified mid-sweep", userId);
    } catch (RuntimeException exception) {
      log.error("Failed to purge unverified account {}", userId, exception);
    }
  }
}
