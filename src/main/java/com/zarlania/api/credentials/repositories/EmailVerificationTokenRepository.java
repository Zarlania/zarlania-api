package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.EmailVerificationToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  void deleteByUserIdAndConsumedAtIsNull(UUID userId);

  void deleteByUserId(UUID userId);
}
