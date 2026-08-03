package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.PasswordCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PasswordCredential}. One row per account, so lookups are by user id. */
public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {

  /**
   * An account's stored password, or empty if it has none — which is not the same as a wrong one.
   */
  Optional<PasswordCredential> findByUserId(UUID userId);

  /** Removes an account's password. Part of purging the account, never of changing the password. */
  void deleteByUserId(UUID userId);
}
