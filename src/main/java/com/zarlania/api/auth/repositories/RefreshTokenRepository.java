package com.zarlania.api.auth.repositories;

import com.zarlania.api.auth.entities.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  // PESSIMISTIC_WRITE closes a check-then-act race in RefreshTokenService.rotate(): without it,
  // two concurrent redemptions of the same not-yet-used token can both read usedAt == null and
  // both succeed, which defeats reuse (theft) detection entirely. The lock forces the second
  // caller to block until the first commits, so it re-reads the row with usedAt already set and
  // correctly takes the reuse path.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  List<RefreshToken> findByFamilyId(UUID familyId);
}
