package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.EmailVerificationTokenEntity;
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

/**
 * Persistence for {@link EmailVerificationTokenEntity}. Everything here is keyed on the token hash
 * or the user, never the raw token, which this side of the system never sees.
 */
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationTokenEntity, UUID> {

  /** Finds a token by its hash, without locking it. For reads that will not go on to write. */
  Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

  /**
   * The locking twin of {@link #findByTokenHash}, for the one caller that goes on to write the row.
   *
   * <p>Without {@code SELECT … FOR UPDATE}, two requests carrying the same token can both read
   * {@code consumedAt} as null before either dirty-check update commits, and both then report a
   * successful verification — the entity has no version column to catch it. Postgres re-evaluates
   * the row against its new version when the lock is released, so the loser sees the {@code
   * consumedAt} the winner wrote and {@code EmailVerificationService#consume} rejects it, which is
   * what makes "single-use" actually true.
   *
   * <p>Kept as a second method rather than an {@code @Lock} on {@link #findByTokenHash}, because a
   * pessimistic lock outside a transaction is an error and the plain finder is also used for
   * non-transactional reads.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<EmailVerificationTokenEntity> findWithLockByTokenHash(String tokenHash);

  /**
   * Invalidates an account's outstanding tokens. Issuing a fresh token runs this first, so only the
   * newest one is ever redeemable.
   */
  void deleteByUserIdAndConsumedAtIsNull(UUID userId);

  /**
   * Takes a transaction-scoped advisory lock on one account, so Postgres releases it at commit or
   * rollback.
   *
   * <p>Held before issuing replaces an account's outstanding token, because the delete-then-insert
   * pair is not atomic on its own: two concurrent issues for the same account can both complete
   * their delete before either insert becomes visible, and each then adds its own row, leaving two
   * live tokens where the design promises that issuing a fresh one invalidates every outstanding
   * one. See {@code EmailVerificationService} for the classifier and key derivation.
   */
  @Query(value = "select pg_advisory_xact_lock(:classifier, :key)", nativeQuery = true)
  void acquireUserTokenLock(@Param("classifier") int classifier, @Param("key") int key);

  /** Removes every token an account holds, consumed or not. Part of purging the account. */
  void deleteByUserId(UUID userId);

  /**
   * Prunes tokens nothing can read again: consumed ones regardless of age, and expired ones past
   * {@code cutoff}.
   *
   * <p>A consumed token is dead the moment it is used, and an expired one can never be consumed, so
   * neither is ever read again. Without this they accumulate for the life of the database — issuing
   * only clears an account's <em>unconsumed</em> tokens.
   *
   * <p>{@code @Transactional} here rather than at the caller: the sweep's entry point is
   * {@code @Scheduled}, and a {@code @Modifying} query needs a transaction of its own to run in.
   *
   * @param cutoff governs the expiry clause only — consumed rows go regardless of age, which is
   *     what the method name spells out rather than leaving to the reader of the query
   * @return how many rows were pruned
   */
  @Modifying
  @Transactional
  @Query(
      "delete from EmailVerificationTokenEntity t"
          + " where t.consumedAt is not null or t.expiresAt < :cutoff")
  int deleteConsumedTokensAndThoseExpiredBefore(@Param("cutoff") Instant cutoff);
}
