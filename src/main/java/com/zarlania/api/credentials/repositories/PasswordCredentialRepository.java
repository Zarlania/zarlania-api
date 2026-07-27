package com.zarlania.api.credentials.repositories;

import com.zarlania.api.credentials.entities.PasswordCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {
  Optional<PasswordCredential> findByUserId(UUID userId);
}
