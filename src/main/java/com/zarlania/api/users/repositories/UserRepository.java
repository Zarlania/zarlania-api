package com.zarlania.api.users.repositories;

import com.zarlania.api.users.entities.UserEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link UserEntity}. Lookups on {@code email} and {@code username} are
 * case-insensitive, because both are {@code citext} columns.
 */
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

  /** Finds an account by address, case-insensitively. */
  Optional<UserEntity> findByEmail(String email);

  /** Finds an account by username, case-insensitively. */
  Optional<UserEntity> findByUsername(String username);

  /** Whether an address is taken, case-insensitively. */
  boolean existsByEmail(String email);

  /** Whether a username is taken, case-insensitively. */
  boolean existsByUsername(String username);

  /**
   * Accounts registered before {@code cutoff} that never verified their address — the candidates
   * for purging. A verified account is never returned however old it is.
   */
  List<UserEntity> findByEmailVerifiedAtIsNullAndCreatedAtBefore(Instant cutoff);

  /**
   * Deletes an account, but only if it is still unverified, as one statement.
   *
   * <p>Deliberately not a derived {@code deleteBy…} query, which Spring Data implements as a select
   * followed by a delete: under {@code READ COMMITTED} that pair can straddle a concurrent
   * verification, and the delete would then go ahead on the strength of a stale read. Expressed
   * this way, Postgres evaluates the clause while holding the row lock it takes to delete, against
   * the row's committed version, so a verification that got there first is always seen and no row
   * is removed.
   *
   * <p>{@code flushAutomatically}, because this executes as SQL and does not see the persistence
   * context: the caller deletes the account's memberships and credentials first, and those are
   * still queued when this runs. Without the flush they reach the database <em>after</em> it, and
   * Postgres rejects the delete on the foreign key from {@code organization_memberships} that they
   * were meant to have cleared. {@code clearAutomatically} for the mirror-image reason — a bulk
   * delete leaves any already-loaded {@code UserEntity} sitting in the persistence context,
   * describing a row that no longer exists.
   *
   * @return the number of rows deleted: 1 normally, 0 if the account verified itself first. That
   *     count is the caller's signal, which is why this returns {@code int} rather than {@code
   *     void}.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from UserEntity u where u.id = :id and u.emailVerifiedAt is null")
  int deleteByIdAndEmailVerifiedAtIsNull(@Param("id") UUID id);
}
