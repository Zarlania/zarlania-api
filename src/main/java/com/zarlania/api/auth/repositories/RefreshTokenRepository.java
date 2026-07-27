package com.zarlania.api.auth.repositories;

import com.zarlania.api.auth.entities.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  List<RefreshToken> findByFamilyId(UUID familyId);

  // A scalar projection, deliberately not an entity load: loading a RefreshToken here would
  // attach it to the persistence context, and Hibernate's identity map would then hand that
  // stale (pre-lock) instance back out of the locked family query below instead of the fresh,
  // just-unblocked row, silently defeating the lock. A projection never enters the identity map,
  // so it can't shadow the locked read that follows it.
  @Query("select r.familyId from RefreshToken r where r.tokenHash = :tokenHash")
  Optional<UUID> findFamilyIdByTokenHash(@Param("tokenHash") String tokenHash);

  // Ascending-id order is a defense-in-depth deadlock-avoidance mechanism, kept alongside the
  // advisory lock below: every code path that locks more than one row of a family goes through
  // this single method, so even if the advisory lock were ever skipped by a future bug, two
  // concurrent callers on the same family would still request row locks in the same sequence and
  // could never form an AB-BA cycle (one waiting on row X while holding row Y, the other waiting
  // on Y while holding X).
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RefreshToken> findByFamilyIdOrderById(UUID familyId);

  // pg_advisory_xact_lock, transaction-scoped: Postgres releases it automatically at commit or
  // rollback. The session-scoped pg_advisory_lock is deliberately not used here — it survives
  // past this method's transaction and would leak the lock back into the connection pool for
  // whichever caller borrows the connection next. Taking this before any family row is read
  // serializes every rotate()/revokeFamilyOf() call on the same family end to end, which closes a
  // gap the row lock alone could not: a revocation whose own locked read blocks on a row an
  // in-flight rotate() holds can, per Postgres's read-committed snapshot rules, unblock without
  // ever seeing the successor row that rotate() inserted — see RefreshTokenService for the key
  // derivation and the two-argument classifier/key split.
  @Query(value = "select pg_advisory_xact_lock(:classifier, :key)", nativeQuery = true)
  void acquireFamilyLock(@Param("classifier") int classifier, @Param("key") int key);
}
