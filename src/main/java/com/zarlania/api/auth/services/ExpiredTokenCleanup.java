package com.zarlania.api.auth.services;

import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.services.EmailVerificationService;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Deletes token rows nothing can ever read again. {@link UnverifiedAccountCleanup} only removes
 * rows belonging to accounts that were never verified, so before this existed nothing at all pruned
 * a verified user's tokens: an active session refreshing on the 15-minute access-token TTL inserts
 * roughly 96 {@code refresh_tokens} rows a day — about 35,000 per user per year — and {@code
 * EmailVerificationService.issue} clears only a user's *unconsumed* tokens, so every consumed one
 * lived forever. The free tier's database is 1 GB.
 *
 * <p>Runs on the same {@code zarlania.auth.cleanup-interval} schedule as the account sweep but as
 * its own bean: pruning dead rows and purging abandoned signups are separate responsibilities that
 * fail independently, and each must still run when the other throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredTokenCleanup {

  private final RefreshTokenRepository refreshTokenRepository;
  private final EmailVerificationService emailVerificationService;
  private final Clock clock;

  /**
   * Deletes refresh-token families past their expiry, and verification tokens nothing can read
   * again.
   *
   * <p>Runs on a schedule rather than on each write: neither table is read on the hot path for rows
   * this old, and sweeping periodically keeps the cost off every request.
   */
  @Scheduled(fixedDelayString = "${zarlania.auth.cleanup-interval:PT1H}")
  public void pruneDeadTokens() {
    int refreshTokensDeleted = refreshTokenRepository.deleteFamiliesExpiredBefore(clock.instant());
    // Through the credentials domain's service, not its repository: that domain decides what one
    // of its tokens being dead means, and this one only owns the schedule.
    int verificationTokensDeleted = emailVerificationService.pruneDeadTokens();
    log.info(
        "Pruned {} expired refresh tokens and {} dead verification tokens",
        refreshTokensDeleted,
        verificationTokensDeleted);
  }
}
