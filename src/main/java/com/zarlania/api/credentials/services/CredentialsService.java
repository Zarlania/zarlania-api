package com.zarlania.api.credentials.services;

import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.PasswordCredential;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordEncoder passwordEncoder;
  private final Semaphore hashingPermits;

  public CredentialsService(
      PasswordCredentialRepository credentials,
      EmailVerificationTokenRepository verificationTokens,
      PasswordEncoder passwordEncoder,
      CredentialsProperties properties) {
    this.credentials = credentials;
    this.verificationTokens = verificationTokens;
    this.passwordEncoder = passwordEncoder;
    this.hashingPermits = new Semaphore(properties.maxConcurrentHashes());
  }

  @Transactional
  public void createPassword(UUID userId, String rawPassword) {
    String passwordHash = hash(() -> passwordEncoder.encode(rawPassword));
    credentials.save(new PasswordCredential(userId, passwordHash));
  }

  // Deliberately not @Transactional: the hash comparison below can wait on the concurrency gate,
  // and holding a pooled connection for the whole of that wait would trade an out-of-memory risk
  // for a connection-pool exhaustion one. The repository call opens and closes its own
  // transaction, and PasswordCredential has no lazy state, so reading its hash afterwards is safe.
  public boolean passwordMatches(UUID userId, String rawPassword) {
    return credentials
        .findByUserId(userId)
        .map(c -> hash(() -> passwordEncoder.matches(rawPassword, c.getPasswordHash())))
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
    DECOY_HASH_SINK.set(hash(() -> passwordEncoder.encode(DECOY_PASSWORD)));
  }

  // Both of this domain's tables in one call, so a caller purging an account never has to know
  // that proof-of-identity material is split across two of them, nor reach for this domain's
  // repositories to clear them. Order between the two does not matter — neither table references
  // the other — but both must go before the users row they share a foreign key with.
  @Transactional
  public void deleteAllForUser(UUID userId) {
    verificationTokens.deleteByUserId(userId);
    credentials.deleteByUserId(userId);
  }

  // Argon2id's 19 MiB working buffer is allocated on the *Java heap* by BouncyCastle, and Tomcat
  // will happily run its whole thread pool against a ~358 MB heap (render.yaml sets
  // -XX:MaxRAMPercentage=70 on a 512 MB instance) — roughly 19 concurrent hashes exhaust it and
  // the container is OOM-killed, which is trivially reachable from unauthenticated /auth/login
  // traffic. This gate caps what the hash path can hold at any instant to
  // zarlania.credentials.max-concurrent-hashes buffers; anything beyond that waits instead of
  // allocating. server.tomcat.threads.max bounds how many can be waiting at once.
  //
  // Every hash in this class goes through here, the decoy included, and that is not incidental:
  // a decoy that skipped the gate would return immediately while a real hash queued behind it,
  // reopening precisely the timing channel hashDecoyPassword() exists to close.
  private <T> T hash(Supplier<T> hashing) {
    try {
      hashingPermits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to hash a password", e);
    }
    try {
      return hashing.get();
    } finally {
      hashingPermits.release();
    }
  }
}
