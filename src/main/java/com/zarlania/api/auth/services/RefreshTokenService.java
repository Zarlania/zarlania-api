package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.common.security.TokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and rotates refresh-token families. Each redemption is single-use; presenting an
 * already-used token is treated as evidence of theft, so the whole family is revoked.
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private final RefreshTokenRepository tokens;
  private final AuthProperties authProperties;
  private final Clock clock;

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

  // noRollbackFor is load-bearing: without it, ReusedRefreshTokenException marks the transaction
  // rollback-only by Spring's default rule for unchecked exceptions, so the revocation below
  // would be silently discarded at commit — exactly the theft-detection bypass this method exists
  // to prevent.
  @Transactional(noRollbackFor = ReusedRefreshTokenException.class)
  public RefreshRotation rotate(String raw) {
    String tokenHash = TokenHasher.sha256Hex(raw);
    UUID familyId =
        tokens.findFamilyIdByTokenHash(tokenHash).orElseThrow(InvalidRefreshTokenException::new);
    // Locks the whole family, in canonical order, before touching any single row — see
    // findByFamilyIdOrderById for why that closes the deadlock a single-row lock left open.
    RefreshToken current = findByHash(tokens.findByFamilyIdOrderById(familyId), tokenHash);
    Instant now = clock.instant();
    if (current.getUsedAt() != null) {
      // revokeFamily re-reads the family from scratch instead of reusing the list above: if this
      // call blocked on the row above, Postgres resolves that wait by refreshing only the row(s)
      // it was already waiting on, not by discovering rows inserted elsewhere in the meantime —
      // so the concurrent winner's freshly inserted successor row would otherwise be invisible
      // here and survive un-revoked. A second, independent query sees it too.
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

  @Transactional
  public void revokeFamilyOf(String raw) {
    tokens
        .findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw))
        .ifPresent(familyId -> revokeFamily(familyId, clock.instant()));
  }

  // MessageDigest.isEqual, not String.equals: the hashes being compared are secrets derived from
  // the caller-supplied raw token, and a short-circuiting equals() leaks how many leading bytes
  // matched through response timing (FindSecBugs UNSAFE_HASH_EQUALS).
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
