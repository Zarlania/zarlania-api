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

  // Ascending-id order is the deadlock-avoidance mechanism: every code path that needs to lock
  // more than one row of a family goes through this single method, so two concurrent callers on
  // the same family always request row locks in the same sequence and can never form an AB-BA
  // cycle (one waiting on row X while holding row Y, the other waiting on Y while holding X).
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RefreshToken> findByFamilyIdOrderById(UUID familyId);
}
