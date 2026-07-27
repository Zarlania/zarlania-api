package com.zarlania.api.credentials.services;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.common.security.TokenHasher;
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

  private final EmailVerificationTokenRepository tokens;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Transactional
  public String issue(UUID userId) {
    tokens.deleteByUserIdAndConsumedAtIsNull(userId);
    String raw = TokenHasher.newUrlSafeToken();
    Instant expiresAt = clock.instant().plus(authProperties.verificationTokenTtl());
    tokens.save(new EmailVerificationToken(userId, TokenHasher.sha256Hex(raw), expiresAt));
    return raw;
  }

  @Transactional
  public Optional<UUID> consume(String rawToken) {
    return tokens
        .findByTokenHash(TokenHasher.sha256Hex(rawToken))
        .filter(token -> token.isUsable(clock.instant()))
        .map(
            token -> {
              token.consume(clock.instant());
              return token.getUserId();
            });
  }
}
