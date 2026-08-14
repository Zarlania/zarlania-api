package com.zarlania.api.auth.repositories;

import com.zarlania.api.auth.entities.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for {@link RefreshTokenEntity}. Several methods here exist specifically to make
 * concurrent rotation and revocation of one family safe; each states what breaks without it.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  /** Finds a token by its hash, without locking it. The raw token is never stored or queried. */
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  /** Every token descended from one login, in no particular order and regardless of state. */
  List<RefreshTokenEntity> findByFamilyId(UUID familyId);

  /**
   * Resolves a token's family without loading the token.
   *
   * <p>A scalar projection, deliberately not an entity load: loading a {@link RefreshTokenEntity}
   * here would attach it to the persistence context, and Hibernate's identity map would then hand
   * that stale, pre-lock instance back out of {@link #findByFamilyIdOrderById} instead of the
   * fresh, just-unblocked row, silently defeating the lock. A projection never enters the identity
   * map, so it cannot shadow the locked read that follows it.
   */
  @Query("select r.familyId from RefreshTokenEntity r where r.tokenHash = :tokenHash")
  Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

  /**
   * Loads and locks every row of a family, in ascending id order.
   *
   * <p>That order is defence in depth against deadlock, kept alongside the advisory lock below:
   * every code path that locks more than one row of a family goes through this single method, so
   * even if the advisory lock were skipped by some future bug, two concurrent callers on the same
   * family would still request row locks in the same sequence and could never form an AB-BA cycle —
   * one waiting on row X while holding row Y, the other waiting on Y while holding X.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RefreshTokenEntity> findByFamilyIdOrderById(UUID familyId);

  /**
   * Takes a transaction-scoped advisory lock on one family, so Postgres releases it automatically
   * at commit or rollback.
   *
   * <p>The session-scoped {@code pg_advisory_lock} is deliberately not used: it survives past this
   * method's transaction and would leak the lock back into the connection pool for whichever caller
   * borrows the connection next.
   *
   * <p>Taken before any family row is read, this serializes every rotation and revocation on the
   * same family end to end, which closes a gap the row lock alone could not: a revocation whose own
   * locked read blocks on a row an in-flight rotation holds can, per Postgres's read-committed
   * snapshot rules, unblock without ever seeing the successor row that rotation inserted. See
   * {@code RefreshTokenService} for the key derivation and the classifier/key split.
   */
  @Query(value = "select pg_advisory_xact_lock(:classifier, :key)", nativeQuery = true)
  void acquireFamilyLock(@Param("classifier") int classifier, @Param("key") int key);

  /**
   * Removes every token an account holds.
   *
   * <p>An unverified account cannot log in, so normally has none — but {@code refresh_tokens}
   * carries real foreign keys on both {@code user_id} and {@code organization_id}, so a purge must
   * clear this defensively before it can delete the account's personal organization or the account
   * row itself.
   */
  void deleteByUserId(UUID userId);

  /**
   * Prunes whole families once past their absolute expiry.
   *
   * <p>Deliberately not "used or revoked". A used token has to stay readable until its family dies,
   * because presenting one a second time is exactly what proves theft and revokes the family;
   * deleting it sooner would turn a replay into an ordinary unknown-token 401 and lose the
   * detection entirely.
   *
   * <p>{@code @Transactional} here rather than at the caller: the sweep's entry point is
   * {@code @Scheduled}, and a {@code @Modifying} query needs a transaction of its own to run in.
   *
   * @return how many rows were pruned
   */
  @Modifying
  @Transactional
  @Query("delete from RefreshTokenEntity r where r.familyExpiresAt < :cutoff")
  int deleteFamiliesExpiredBefore(@Param("cutoff") Instant cutoff);
}
