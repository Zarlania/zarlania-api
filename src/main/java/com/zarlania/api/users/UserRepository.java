package com.zarlania.api.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for {@link User}. Internal to the {@code users} domain. */
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Finds a user by exact email.
   *
   * @param email the email to match
   * @return the user, if one exists
   */
  Optional<User> findByEmail(String email);

  /**
   * Reports whether a user with the given email exists.
   *
   * @param email the email to check
   * @return {@code true} if a user with that email exists
   */
  boolean existsByEmail(String email);
}
