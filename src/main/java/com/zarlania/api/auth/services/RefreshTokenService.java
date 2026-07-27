package com.zarlania.api.auth.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.common.security.TokenHasher;
import java.time.Clock;
import java.time.Instant;
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
    RefreshToken current =
        tokens
            .findByTokenHash(TokenHasher.sha256Hex(raw))
            .orElseThrow(InvalidRefreshTokenException::new);
    Instant now = clock.instant();
    if (current.getUsedAt() != null) {
      revokeFamily(current.getFamilyId(), now); // reuse = theft signal
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
        .findByTokenHash(TokenHasher.sha256Hex(raw))
        .ifPresent(token -> revokeFamily(token.getFamilyId(), clock.instant()));
  }

  private void revokeFamily(UUID familyId, Instant now) {
    tokens.findByFamilyId(familyId).stream()
        .filter(token -> token.getRevokedAt() == null)
        .forEach(token -> token.revoke(now));
  }
}
