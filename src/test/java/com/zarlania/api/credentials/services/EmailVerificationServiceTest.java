package com.zarlania.api.credentials.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.EmailVerificationTokenEntity;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.security.TokenHasher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  private static final Duration VERIFICATION_TOKEN_TTL = Duration.ofHours(24);

  @Mock private EmailVerificationTokenRepository tokens;

  private Clock clock;
  private EmailVerificationService service;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new EmailVerificationService(
            tokens, new CredentialsProperties(VERIFICATION_TOKEN_TTL, 4), clock);
  }

  @Test
  void issueDeletesPriorUnconsumedTokensBeforeSavingTheNewOne() {
    UUID userId = UUID.randomUUID();

    service.issue(userId);

    verify(tokens).deleteByUserIdAndConsumedAtIsNull(userId);
  }

  // Order is the whole point. Taken after the delete instead, two concurrent issues for the same
  // user could both clear the outstanding tokens before either insert became visible, and each
  // would then add its own — leaving two live tokens where issuing a fresh one is supposed to
  // invalidate every outstanding one.
  @Test
  void issueTakesThePerUserLockBeforeClearingOutstandingTokens() {
    UUID userId = UUID.randomUUID();

    service.issue(userId);

    InOrder inOrder = inOrder(tokens);
    inOrder.verify(tokens).acquireUserTokenLock(anyInt(), anyInt());
    inOrder.verify(tokens).deleteByUserIdAndConsumedAtIsNull(userId);
    inOrder.verify(tokens).save(any(EmailVerificationTokenEntity.class));
  }

  @Test
  void issueSavesAHashOfTheRawTokenRatherThanTheRawTokenItself() {
    UUID userId = UUID.randomUUID();

    String raw = service.issue(userId);

    ArgumentCaptor<EmailVerificationTokenEntity> saved =
        ArgumentCaptor.forClass(EmailVerificationTokenEntity.class);
    verify(tokens).save(saved.capture());
    assertThat(saved.getValue().getTokenHash()).isNotEqualTo(raw);
    assertThat(saved.getValue().getTokenHash()).isEqualTo(TokenHasher.sha256Hex(raw));
  }

  @Test
  void issueSetsExpiryToNowPlusTheConfiguredTtl() {
    UUID userId = UUID.randomUUID();

    service.issue(userId);

    ArgumentCaptor<EmailVerificationTokenEntity> saved =
        ArgumentCaptor.forClass(EmailVerificationTokenEntity.class);
    verify(tokens).save(saved.capture());
    assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(VERIFICATION_TOKEN_TTL));
  }

  @Test
  void consumeOnAUsableTokenStampsItConsumedAndReturnsTheUserId() {
    UUID userId = UUID.randomUUID();
    String raw = "raw-token";
    EmailVerificationTokenEntity token =
        new EmailVerificationTokenEntity(userId, TokenHasher.sha256Hex(raw), NOW.plusSeconds(60));
    when(tokens.findWithLockByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.of(token));

    Optional<UUID> result = service.consume(raw);

    assertThat(result).contains(userId);
    assertThat(token.getConsumedAt()).isEqualTo(NOW);
  }

  @Test
  void consumeOnAnExpiredTokenReturnsEmpty() {
    UUID userId = UUID.randomUUID();
    String raw = "raw-token";
    EmailVerificationTokenEntity token =
        new EmailVerificationTokenEntity(userId, TokenHasher.sha256Hex(raw), NOW.minusSeconds(1));
    when(tokens.findWithLockByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.of(token));

    Optional<UUID> result = service.consume(raw);

    assertThat(result).isEmpty();
  }

  @Test
  void consumeAdvancedTwentyFiveHoursPastIssueReturnsEmptyForAnExpiredToken() {
    UUID userId = UUID.randomUUID();
    String raw = "raw-token";
    Instant expiresAt = NOW.plus(VERIFICATION_TOKEN_TTL);
    EmailVerificationTokenEntity token =
        new EmailVerificationTokenEntity(userId, TokenHasher.sha256Hex(raw), expiresAt);
    when(tokens.findWithLockByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.of(token));
    clock = Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC);
    service =
        new EmailVerificationService(
            tokens, new CredentialsProperties(VERIFICATION_TOKEN_TTL, 4), clock);

    Optional<UUID> result = service.consume(raw);

    assertThat(result).isEmpty();
  }

  @Test
  void consumeOnAnAlreadyConsumedTokenReturnsEmpty() {
    UUID userId = UUID.randomUUID();
    String raw = "raw-token";
    EmailVerificationTokenEntity token =
        new EmailVerificationTokenEntity(userId, TokenHasher.sha256Hex(raw), NOW.plusSeconds(60));
    token.consume(NOW.minusSeconds(30));
    when(tokens.findWithLockByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.of(token));

    Optional<UUID> result = service.consume(raw);

    assertThat(result).isEmpty();
  }

  @Test
  void consumeOnAnUnknownHashReturnsEmpty() {
    String raw = "does-not-exist";
    when(tokens.findWithLockByTokenHash(TokenHasher.sha256Hex(raw))).thenReturn(Optional.empty());

    Optional<UUID> result = service.consume(raw);

    assertThat(result).isEmpty();
  }
}
