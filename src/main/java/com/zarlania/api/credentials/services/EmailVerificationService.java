package com.zarlania.api.credentials.services;

import com.zarlania.api.common.security.TokenHasher;
import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.EmailVerificationToken;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  private final EmailVerificationTokenRepository tokens;
  private final CredentialsProperties credentialsProperties;
  private final Clock clock;

  @Transactional
  public String issue(UUID userId) {
    // Before the delete, not after: the point is to serialize the whole delete-then-insert pair
    // against another issue() for the same user, and a lock taken afterwards would let both
    // callers' deletes run first — the exact interleaving that leaves two live tokens behind.
    lockUserTokens(userId);
    tokens.deleteByUserIdAndConsumedAtIsNull(userId);
    String raw = TokenHasher.newUrlSafeToken();
    Instant expiresAt = clock.instant().plus(credentialsProperties.verificationTokenTtl());
    tokens.save(new EmailVerificationToken(userId, TokenHasher.sha256Hex(raw), expiresAt));
    return raw;
  }

  // Exposed for the auth domain's hourly sweep, which owns the schedule but must not reach into
  // this domain's repository to run it. Returns how many rows went, so the caller can log it.
  @Transactional
  public int pruneDeadTokens() {
    return tokens.deleteConsumedTokensAndThoseExpiredBefore(clock.instant());
  }

  // The locking finder, not the plain one: this method reads consumedAt and then writes it, so a
  // second request carrying the same token must not be allowed to read the row in between. See
  // EmailVerificationTokenRepository#findWithLockByTokenHash.
  @Transactional
  public Optional<UUID> consume(String rawToken) {
    return tokens
        .findWithLockByTokenHash(TokenHasher.sha256Hex(rawToken))
        .filter(token -> token.isUsable(clock.instant()))
        .map(
            token -> {
              token.consume(clock.instant());
              return token.getUserId();
            });
  }

  // A pure function of userId: XORing the UUID's two halves together folds all 128 bits of entropy
  // into the 32-bit key pg_advisory_xact_lock(int, int) takes, so the same user always produces the
  // same key. A collision between two different users only makes them serialize against each other
  // unnecessarily — never a correctness problem, just lost concurrency.
  private void lockUserTokens(UUID userId) {
    long msb = userId.getMostSignificantBits();
    long lsb = userId.getLeastSignificantBits();
    int key = (int) (msb ^ (msb >>> LONG_HALF_WIDTH_BITS) ^ lsb ^ (lsb >>> LONG_HALF_WIDTH_BITS));
    tokens.acquireUserTokenLock(USER_TOKEN_LOCK_CLASSIFIER, key);
  }
}
