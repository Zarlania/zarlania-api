package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.EmailVerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  void deleteByUserIdAndConsumedAtIsNull(UUID userId);

  void deleteByUserId(UUID userId);

  // A consumed token is dead the moment it is used (consume() is single-use) and an expired one can
  // never be consumed, so neither is ever read again. Without this they accumulate for the life of
  // the database: issue() only clears a user's *unconsumed* tokens.
  //
  // The cutoff governs the expiry clause only — consumed rows go regardless of age, which is what
  // the method name spells out rather than leaving to the reader of the query.
  //
  // @Transactional here rather than at the caller: the sweep's entry point is @Scheduled, and a
  // @Modifying query needs a transaction of its own to run in.
  @Modifying
  @Transactional
  @Query(
      "delete from EmailVerificationToken t"
          + " where t.consumedAt is not null or t.expiresAt < :cutoff")
  int deleteConsumedTokensAndThoseExpiredBefore(@Param("cutoff") Instant cutoff);
}
