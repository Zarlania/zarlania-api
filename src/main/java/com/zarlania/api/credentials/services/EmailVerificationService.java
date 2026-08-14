package com.zarlania.api.credentials.services;

import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.EmailVerificationTokenEntity;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.security.TokenHasher;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the single-use tokens that prove someone can read the address they registered
 * with.
 *
 * <p>Two promises hold this together, and each needs a lock to be true under concurrency: issuing a
 * fresh token invalidates every outstanding one, and a token can be redeemed exactly once. Only the
 * SHA-256 hash is ever stored, so the raw token exists only in the email that was sent.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

  // Arbitrary but fixed first argument to pg_advisory_xact_lock(int, int). Advisory locks share one
  // flat 64-bit space per database, and the two-argument form exists so each feature that takes one
  // can reserve a distinct first argument; RefreshTokenService holds "RFTL" and this is the second
  // such feature, so it gets its own rather than reusing that. ("EVTL": EmailVerificationTokenLock,
  // spelled out in hex.)
  private static final int USER_TOKEN_LOCK_CLASSIFIER = 0x4556_544C;

  // Half the width of the long halves folded together below, to fold each into its own 32-bit
  // contribution rather than just discarding its top bits.
  private static final int LONG_HALF_WIDTH_BITS = 32;

  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final CredentialsProperties credentialsProperties;
  private final Clock clock;

  /**
   * Issues a fresh token, invalidating every outstanding one for the account.
   *
   * @return the raw token, which the caller emails and this application never stores or sees again
   */
  @Transactional
  public String issue(UUID userId) {
    // Before the delete, not after: the point is to serialize the whole delete-then-insert pair
    // against another issue() for the same user, and a lock taken afterwards would let both
    // callers' deletes run first — the exact interleaving that leaves two live tokens behind.
    lockUserTokens(userId);
    emailVerificationTokenRepository.deleteByUserIdAndConsumedAtIsNull(userId);
    String raw = TokenHasher.newUrlSafeToken();
    Instant expiresAt = clock.instant().plus(credentialsProperties.verificationTokenTtl());
    emailVerificationTokenRepository.save(
        new EmailVerificationTokenEntity(userId, TokenHasher.sha256Hex(raw), expiresAt));
    return raw;
  }

  /**
   * Prunes tokens nothing can read again — consumed ones, and expired ones.
   *
   * <p>Exposed for the auth domain's hourly sweep, which owns the schedule but must not reach into
   * this domain's repository to run it.
   *
   * @return how many rows went, so the caller can log it
   */
  @Transactional
  public int pruneDeadTokens() {
    return emailVerificationTokenRepository.deleteConsumedTokensAndThoseExpiredBefore(
        clock.instant());
  }

  /**
   * Redeems a token, if it is still redeemable.
   *
   * <p>Uses the locking finder, not the plain one: this reads {@code consumedAt} and then writes
   * it, so a second request carrying the same token must not be allowed to read the row in between.
   * See {@link EmailVerificationTokenRepository#findWithLockByTokenHash}.
   *
   * @return the account the token verifies, or empty if it is unknown, expired or already consumed
   *     — the three are indistinguishable to the caller on purpose
   */
  @Transactional
  public Optional<UUID> consume(String rawToken) {
    return emailVerificationTokenRepository
        .findWithLockByTokenHash(TokenHasher.sha256Hex(rawToken))
        .filter(token -> token.isUsable(clock.instant()))
        .map(
            token -> {
              token.consume(clock.instant());
              return token.getUserId();
            });
  }

  /**
   * A pure function of userId: XORing the UUID's two halves together folds all 128 bits of entropy
   * into the 32-bit key pg_advisory_xact_lock(int, int) takes, so the same user always produces the
   * same key. A collision between two different users only makes them serialize against each other
   * unnecessarily — never a correctness problem, just lost concurrency.
   */
  private void lockUserTokens(UUID userId) {
    long msb = userId.getMostSignificantBits();
    long lsb = userId.getLeastSignificantBits();
    int key = (int) (msb ^ (msb >>> LONG_HALF_WIDTH_BITS) ^ lsb ^ (lsb >>> LONG_HALF_WIDTH_BITS));
    emailVerificationTokenRepository.acquireUserTokenLock(USER_TOKEN_LOCK_CLASSIFIER, key);
  }
}
