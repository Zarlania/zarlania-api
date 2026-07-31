package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  // The locking twin of findByTokenHash above, for the one caller that goes on to write the row.
  // Without SELECT ... FOR UPDATE, two requests carrying the same token can both read consumedAt as
  // null before either dirty-check update commits, and both then report a successful verification —
  // the entity has no version column to catch it. Postgres re-evaluates the row against its new
  // version when the lock is released, so the loser sees the consumedAt the winner wrote and
  // EmailVerificationService#consume rejects it, which is what makes "single-use" actually true.
  //
  // Kept as a second method rather than an @Lock on findByTokenHash, because a pessimistic lock
  // outside a transaction is an error and the plain finder is also used for non-transactional
  // reads.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EmailVerificationToken> findWithLockByTokenHash(String tokenHash);

  void deleteByUserIdAndConsumedAtIsNull(UUID userId);

  // pg_advisory_xact_lock, transaction-scoped so Postgres releases it at commit or rollback, taken
  // on the user before issue() replaces their outstanding token. The delete-then-insert pair is not
  // atomic on its own: two concurrent issues for the same user can both complete their delete
  // before either insert becomes visible, and each then adds its own row, leaving two live tokens
  // where the design promises that issuing a fresh one invalidates every outstanding one. See
  // EmailVerificationService for the classifier and key derivation.
  @Query(value = "select pg_advisory_xact_lock(:classifier, :key)", nativeQuery = true)
  void acquireUserTokenLock(@Param("classifier") int classifier, @Param("key") int key);

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
