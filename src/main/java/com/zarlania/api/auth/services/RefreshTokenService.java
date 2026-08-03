package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.security.TokenHasher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and rotates refresh-token families. Each redemption is single-use; presenting an
 * already-used token is treated as evidence of theft, so the whole family is revoked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  // Greppable marker on the reuse log line, following RegistrationEmailListener's convention.
  // The caller sees the same 401 as any bad token — deliberately, so a thief learns nothing —
  // which makes this line the only place theft detection is visible at all. An operator alert
  // matches on the marker; the user and family ids in the line are what an investigation
  // pivots on.
  private static final String REUSE_LOG_MARKER = "REFRESH_TOKEN_REUSE";

  // Arbitrary but fixed first argument to pg_advisory_xact_lock(int, int): Postgres advisory
  // locks are one flat 64-bit space per database, and the two-argument form exists precisely so
  // each feature that takes one can reserve a distinct first argument, keeping unrelated features'
  // locks from ever coinciding. If another feature starts taking advisory locks, give it its own
  // classifier rather than reusing this one. ("RFTL": ReFreshTokenLock, spelled out in hex.)
  private static final int FAMILY_LOCK_CLASSIFIER = 0x5246_544C;

  // Half the width of the long halves folded together below, to fold each into its own 32-bit
  // contribution rather than just discarding its top bits.
  private static final int LONG_HALF_WIDTH_BITS = 32;

  private final RefreshTokenRepository tokens;
  private final AuthProperties authProperties;
  private final Clock clock;

  /**
   * Opens a new family and issues its first token. Called once per login; every later token in the
   * session descends from this one.
   *
   * @return the raw token, which exists only here and in the client's cookie — the row stores its
   *     hash
   */
  @Transactional
  public IssuedRefreshToken startFamily(UUID userId, UUID organizationId) {
    String raw = TokenHasher.newUrlSafeToken();
    Instant familyExpiresAt = clock.instant().plus(authProperties.refreshFamilyLifetime());
    tokens.save(
        new RefreshToken(
            UUID.randomUUID(),
            userId,
            organizationId,
            TokenHasher.sha256Hex(raw),
            familyExpiresAt));
    return new IssuedRefreshToken(raw, familyExpiresAt);
  }

  /**
   * Redeems a token and issues its successor into the same family.
   *
   * <p>{@code noRollbackFor} is load-bearing: without it, {@link ReusedRefreshTokenException} marks
   * the transaction rollback-only by Spring's default rule for unchecked exceptions, so the
   * revocation this method performs would be silently discarded at commit — exactly the
   * theft-detection bypass it exists to prevent.
   *
   * @throws ReusedRefreshTokenException if the token was already redeemed, which revokes the whole
   *     family: two parties hold tokens from one session, and there is no way to tell which is the
   *     thief
   * @throws InvalidRefreshTokenException if the token is unknown, revoked, or past its family's
   *     lifetime
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "The reuse log line's arguments are java.util.UUIDs, whose toString() is always"
              + " lowercase hex digits and hyphens (RFC 4122) — there is no injectable character"
              + " to strip; same reasoning as UnverifiedAccountCleanup#purgeSafely.")
  @Transactional(noRollbackFor = ReusedRefreshTokenException.class)
  public RefreshRotation rotate(String raw) {
    String tokenHash = TokenHasher.sha256Hex(raw);
    UUID familyId =
        tokens.findFamilyIdByTokenHash(tokenHash).orElseThrow(InvalidRefreshTokenException::new);
    // Serializes every rotate()/revokeFamilyOf() call on this family before touching a single
    // row — see acquireFamilyLock for why the row lock below is not enough on its own.
    lockFamily(familyId);
    RefreshToken current = findByHash(tokens.findByFamilyIdOrderById(familyId), tokenHash);
    Instant now = clock.instant();
    if (current.getUsedAt() != null) {
      // Both ids are java.util.UUIDs, so the line is CRLF-safe by construction; the raw token and
      // its hash stay out of it deliberately — the log must never become a place tokens leak.
      log.warn(
          "{}: refresh token replayed for user {} — revoking family {}",
          REUSE_LOG_MARKER,
          current.getUserId(),
          familyId);
      revokeFamily(familyId, now); // reuse = theft signal
      throw new ReusedRefreshTokenException();
    }
    if (!current.isActive(now)) {
      throw new InvalidRefreshTokenException();
    }
    current.markUsed(now);
    String newRaw = TokenHasher.newUrlSafeToken();
    tokens.save(
        new RefreshToken(
            current.getFamilyId(),
            current.getUserId(),
            current.getOrganizationId(),
            TokenHasher.sha256Hex(newRaw),
            current.getFamilyExpiresAt()));
    return new RefreshRotation(
        newRaw, current.getUserId(), current.getOrganizationId(), current.getFamilyExpiresAt());
  }

  /**
   * Revokes every unrevoked row in the family a token belongs to. A no-op for an unknown token, so
   * that logout says nothing about whether the token was real.
   */
  @Transactional
  public void revokeFamilyOf(String raw) {
    tokens
        .findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw))
        .ifPresent(
            familyId -> {
              lockFamily(familyId);
              revokeFamily(familyId, clock.instant());
            });
  }

  /**
   * A pure function of familyId: XORing the UUID's two halves together folds all 128 bits of
   * entropy into the 32-bit key pg_advisory_xact_lock(int, int) takes, so the same family always
   * produces the same key. A collision between two different families only makes them serialize
   * against each other unnecessarily — never a correctness problem, just lost concurrency.
   */
  private void lockFamily(UUID familyId) {
    long msb = familyId.getMostSignificantBits();
    long lsb = familyId.getLeastSignificantBits();
    int key = (int) (msb ^ (msb >>> LONG_HALF_WIDTH_BITS) ^ lsb ^ (lsb >>> LONG_HALF_WIDTH_BITS));
    tokens.acquireFamilyLock(FAMILY_LOCK_CLASSIFIER, key);
  }

  /**
   * MessageDigest.isEqual, not String.equals: the hashes being compared are secrets derived from
   * the caller-supplied raw token, and a short-circuiting equals() leaks how many leading bytes
   * matched through response timing (FindSecBugs UNSAFE_HASH_EQUALS).
   */
  private RefreshToken findByHash(List<RefreshToken> family, String tokenHash) {
    byte[] target = tokenHash.getBytes(StandardCharsets.UTF_8);
    return family.stream()
        .filter(
            token ->
                MessageDigest.isEqual(
                    token.getTokenHash().getBytes(StandardCharsets.UTF_8), target))
        .findFirst()
        .orElseThrow(InvalidRefreshTokenException::new);
  }

  private void revokeFamily(UUID familyId, Instant now) {
    tokens.findByFamilyIdOrderById(familyId).stream()
        .filter(token -> token.getRevokedAt() == null)
        .forEach(token -> token.revoke(now));
  }
}
