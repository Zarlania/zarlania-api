package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.PasswordCredentialEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link PasswordCredentialEntity}. One row per account, so lookups are by user id.
 */
public interface PasswordCredentialRepository
    extends JpaRepository<PasswordCredentialEntity, UUID> {

  /**
   * An account's stored password, or empty if it has none — which is not the same as a wrong one.
   */
  Optional<PasswordCredentialEntity> findByUserId(UUID userId);

  /** Removes an account's password. Part of purging the account, never of changing the password. */
  void deleteByUserId(UUID userId);
}
