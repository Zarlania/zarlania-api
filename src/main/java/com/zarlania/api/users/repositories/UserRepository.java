package com.zarlania.api.users.repositories;

import com.zarlania.api.users.entities.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);

  Optional<User> findByUsername(String username);

  boolean existsByEmail(String email);

  boolean existsByUsername(String username);

  List<User> findByEmailVerifiedAtIsNullAndCreatedAtBefore(Instant cutoff);

  // "Delete this user, but only if they are still unverified", as one statement. Deliberately not a
  // derived deleteBy… query, which Spring Data implements as a select followed by a delete: under
  // READ COMMITTED that pair can straddle a concurrent verification, and the delete would then go
  // ahead on the strength of a stale read. Expressed this way, Postgres evaluates the clause while
  // holding the row lock it takes to delete, against the row's committed version, so a verification
  // that got there first is always seen and no row is removed.
  //
  // The row count is the caller's signal, which is why this returns int rather than void.
  //
  // flushAutomatically, because this executes as SQL and does not see the persistence context: the
  // caller deletes the user's memberships and credentials first, and those are still queued when
  // this runs. Without the flush they reach the database *after* it, and Postgres rejects the
  // delete on the FK from organization_memberships that they were meant to have cleared.
  // clearAutomatically for the mirror-image reason — a bulk delete leaves any already-loaded User
  // sitting in the persistence context, describing a row that no longer exists.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from User u where u.id = :id and u.emailVerifiedAt is null")
  int deleteByIdAndEmailVerifiedAtIsNull(@Param("id") UUID id);
}
