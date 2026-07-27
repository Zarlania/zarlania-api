package com.zarlania.api.credentials.services;

import com.zarlania.api.credentials.entities.PasswordCredential;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CredentialsService {

  // Never compared against or stored — hashDecoyPassword() below only cares that hashing this
  // costs the same as hashing a real password, not what the value is.
  private static final String DECOY_PASSWORD = "registration-timing-parity-decoy-password";

  // Written but deliberately never read. The point of hashDecoyPassword() is the encode() call's
  // cost, not its result, but a result nothing ever reads is exactly what the JIT is licensed to
  // treat as dead work and skip. Storing it in a static field gives the call an externally
  // observable effect that cannot be proven unobservable, so the hashing itself cannot be
  // optimized away. AtomicReference rather than a plain field because this runs from concurrent
  // requests.
  private static final AtomicReference<String> DECOY_HASH_SINK = new AtomicReference<>();

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

  // RegistrationService calls this from every branch of /auth/register and /auth/resend that
  // would otherwise return without hashing anything (an already-registered email, an unknown
  // email, an already-verified account). Argon2id at PasswordEncoderConfig's parameters (19 MiB,
  // 2 iterations) costs tens of milliseconds, versus roughly a millisecond for the single SELECT
  // those branches would otherwise run — a gap trivially measurable over a network. Paying that
  // same dominant cost on a fixed, discarded value closes it without touching a real password or
  // writing to the database.
  public void hashDecoyPassword() {
    DECOY_HASH_SINK.set(passwordEncoder.encode(DECOY_PASSWORD));
  }
}
