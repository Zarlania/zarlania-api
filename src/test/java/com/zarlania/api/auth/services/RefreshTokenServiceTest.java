package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshTokenEntity;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.security.TokenHasher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The rotation rules, against a mocked repository.
 *
 * <p>Everything asserted here is a decision the service makes rather than something the database
 * enforces: what a raw token is turned into before it is stored, which state produces which
 * exception, and whether the family lock is taken before any row is read. The database-backed
 * counterparts are {@code RefreshTokenServiceIntegrationTest} and {@code
 * RefreshTokenServiceTransactionTest}.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration FAMILY_LIFETIME = Duration.ofDays(30);

  @Mock private RefreshTokenRepository tokens;

  @Captor private ArgumentCaptor<RefreshTokenEntity> savedToken;

  private RefreshTokenService refreshTokenService;

  @BeforeEach
  void createService() {
    AuthProperties authProperties =
        new AuthProperties(
            "https://api.zarlania.test",
            Duration.ofMinutes(15),
            FAMILY_LIFETIME,
            Duration.ofDays(7),
            true,
            "",
            "",
            "https://zarlania.test");
    refreshTokenService =
        new RefreshTokenService(tokens, authProperties, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  // The row must never carry anything redeemable: a database disclosure has to yield hashes only.
  @Test
  void startFamilyStoresTheHashRatherThanTheTokenItHandsBack() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, organizationId);

    verify(tokens).save(savedToken.capture());
    assertThat(savedToken.getValue().getTokenHash())
        .isEqualTo(TokenHasher.sha256Hex(issued.raw()))
        .isNotEqualTo(issued.raw());
    assertThat(savedToken.getValue().getUserId()).isEqualTo(userId);
    assertThat(savedToken.getValue().getOrganizationId()).isEqualTo(organizationId);
  }

  @Test
  void startFamilyDatesTheFamilyFromTheClockPlusTheConfiguredLifetime() {
    IssuedRefreshToken issued =
        refreshTokenService.startFamily(UUID.randomUUID(), UUID.randomUUID());

    assertThat(issued.familyExpiresAt()).isEqualTo(NOW.plus(FAMILY_LIFETIME));
  }

  @Test
  void rotatingAnUnknownTokenIsInvalidAndNeverTakesTheFamilyLock() {
    when(tokens.findFamilyIdByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> refreshTokenService.rotate("nobody-issued-this"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(tokens, never()).acquireFamilyLock(anyInt(), anyInt());
  }

  // The lock has to be taken before the family is read, not after: a lock acquired afterwards
  // leaves the read itself unserialized, which is the whole gap it exists to close.
  @Test
  void rotatingTakesTheFamilyLockBeforeReadingAnyRow() {
    String raw = seedLiveToken("lock-order");

    refreshTokenService.rotate(raw);

    InOrder order = Mockito.inOrder(tokens);
    order.verify(tokens).acquireFamilyLock(anyInt(), anyInt());
    order.verify(tokens).findByFamilyIdOrderById(any());
  }

  @Test
  void rotatingIssuesASuccessorIntoTheSameFamilyCarryingTheSameExpiry() {
    String raw = seedLiveToken("successor");

    RefreshRotation rotation = refreshTokenService.rotate(raw);

    verify(tokens).save(savedToken.capture());
    RefreshTokenEntity successor = savedToken.getValue();
    assertThat(successor.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(rotation.newRaw()));
    assertThat(successor.getFamilyExpiresAt()).isEqualTo(NOW.plus(FAMILY_LIFETIME));
    assertThat(rotation.userId()).isEqualTo(successor.getUserId());
  }

  // Reuse revokes the family and then throws, in that order. Throwing first would leave the stolen
  // family live, which is the entire point of detecting the replay.
  @Test
  void rotatingAnAlreadyRedeemedTokenRevokesEveryRowBeforeThrowing() {
    UUID familyId = UUID.randomUUID();
    String raw = TokenHasher.newUrlSafeToken();
    RefreshTokenEntity used = liveToken(familyId, raw);
    used.markUsed(NOW.minusSeconds(1));
    RefreshTokenEntity sibling = liveToken(familyId, TokenHasher.newUrlSafeToken());
    when(tokens.findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(Optional.of(familyId));
    when(tokens.findByFamilyIdOrderById(familyId)).thenReturn(List.of(used, sibling));

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(ReusedRefreshTokenException.class);

    assertThat(used.getRevokedAt()).isEqualTo(NOW);
    assertThat(sibling.getRevokedAt()).isEqualTo(NOW);
    verify(tokens, never()).save(any());
  }

  @Test
  void rotatingARevokedTokenIsInvalidRatherThanReuse() {
    UUID familyId = UUID.randomUUID();
    String raw = TokenHasher.newUrlSafeToken();
    RefreshTokenEntity revoked = liveToken(familyId, raw);
    revoked.revoke(NOW.minusSeconds(1));
    when(tokens.findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(Optional.of(familyId));
    when(tokens.findByFamilyIdOrderById(familyId)).thenReturn(List.of(revoked));

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rotatingATokenPastItsFamilysLifetimeIsInvalid() {
    UUID familyId = UUID.randomUUID();
    String raw = TokenHasher.newUrlSafeToken();
    RefreshTokenEntity expired =
        new RefreshTokenEntity(
            familyId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            TokenHasher.sha256Hex(raw),
            NOW.minusSeconds(1));
    when(tokens.findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(Optional.of(familyId));
    when(tokens.findByFamilyIdOrderById(familyId)).thenReturn(List.of(expired));

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  // Logout must say nothing about whether the token was real, so an unknown one is silence rather
  // than an error — and nothing is locked or written on the way.
  @Test
  void revokingAnUnknownTokenIsSilentAndTouchesNothing() {
    when(tokens.findFamilyIdByTokenHash(any())).thenReturn(Optional.empty());

    assertThatCode(() -> refreshTokenService.revokeFamilyOf("nobody-issued-this"))
        .doesNotThrowAnyException();

    verify(tokens, never()).acquireFamilyLock(anyInt(), anyInt());
    verify(tokens, never()).findByFamilyIdOrderById(any());
  }

  @Test
  void revokingMarksEveryUnrevokedRowInTheFamily() {
    UUID familyId = UUID.randomUUID();
    String raw = TokenHasher.newUrlSafeToken();
    RefreshTokenEntity first = liveToken(familyId, raw);
    RefreshTokenEntity second = liveToken(familyId, TokenHasher.newUrlSafeToken());
    when(tokens.findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(Optional.of(familyId));
    when(tokens.findByFamilyIdOrderById(familyId)).thenReturn(List.of(first, second));

    refreshTokenService.revokeFamilyOf(raw);

    assertThat(first.getRevokedAt()).isEqualTo(NOW);
    assertThat(second.getRevokedAt()).isEqualTo(NOW);
  }

  // The same family always has to derive the same lock key, or two callers on one family would take
  // different locks and serialize against nothing at all.
  @Test
  void theFamilyLockKeyIsAPureFunctionOfTheFamilyId() {
    String raw = seedLiveToken("stable-key");
    ArgumentCaptor<Integer> key = ArgumentCaptor.forClass(Integer.class);

    refreshTokenService.rotate(raw);
    refreshTokenService.revokeFamilyOf(raw);

    verify(tokens, Mockito.atLeast(2)).acquireFamilyLock(anyInt(), key.capture());
    assertThat(key.getAllValues()).containsOnly(key.getAllValues().getFirst());
  }

  private String seedLiveToken(String slug) {
    UUID familyId = UUID.nameUUIDFromBytes(slug.getBytes(StandardCharsets.UTF_8));
    String raw = TokenHasher.newUrlSafeToken();
    when(tokens.findFamilyIdByTokenHash(TokenHasher.sha256Hex(raw)))
        .thenReturn(Optional.of(familyId));
    when(tokens.findByFamilyIdOrderById(eq(familyId)))
        .thenReturn(List.of(liveToken(familyId, raw)));
    return raw;
  }

  private static RefreshTokenEntity liveToken(UUID familyId, String raw) {
    return new RefreshTokenEntity(
        familyId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        TokenHasher.sha256Hex(raw),
        NOW.plus(FAMILY_LIFETIME));
  }
}
