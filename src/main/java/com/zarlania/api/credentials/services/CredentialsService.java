package com.zarlania.api.credentials.services;

import com.zarlania.api.credentials.entities.PasswordCredential;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CredentialsService {

  private final PasswordCredentialRepository credentials;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public void createPassword(UUID userId, String rawPassword) {
    credentials.save(new PasswordCredential(userId, passwordEncoder.encode(rawPassword)));
  }

  @Transactional(readOnly = true)
  public boolean passwordMatches(UUID userId, String rawPassword) {
    return credentials
        .findByUserId(userId)
        .map(c -> passwordEncoder.matches(rawPassword, c.getPasswordHash()))
        .orElse(false);
  }
}
