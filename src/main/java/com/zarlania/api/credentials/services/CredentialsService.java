package com.zarlania.api.credentials.services;

import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.PasswordCredentialEntity;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passwords: hashing them, checking them, and bounding how many hashes can be in flight at once.
 *
 * <p>Two concerns run through everything here. Argon2id holds a 19 MiB buffer on the Java heap per
 * hash, so unbounded concurrency is an out-of-memory kill reachable from unauthenticated traffic —
 * hence the permit gate every hash passes through. And a branch that skips hashing returns
 * measurably faster than one that does not, so callers with nothing to hash burn the same cost on a
 * decoy rather than leaking which branch they took.
 */
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

  private final PasswordCredentialRepository passwordCredentialRepository;
  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordEncoder passwordEncoder;
  private final Semaphore hashingPermits;

  /**
   * Builds the service and sizes the hashing gate.
   *
   * <p>{@code EI_EXPOSE_REP2} is suppressed because the "externally mutable objects" it flags are
   * Spring Data repository proxies: stateless, container-managed singletons shared by every bean
   * that injects them, which is the entire point of dependency injection. There is no
   * representation to copy and nothing a caller could mutate. Every other service here stores the
   * same repositories the same way and goes unflagged only because Lombok marks its generated
   * constructor.
   *
   * @param properties supplies the hashing concurrency cap, which is why this constructor is
   *     written by hand rather than generated: the semaphore is derived from configuration, not
   *     injected
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Spring Data repository proxies are stateless container-managed singletons, not a"
              + " mutable representation; copying one is meaningless. Flagged only because this"
              + " constructor is hand-written rather than Lombok-generated.")
  public CredentialsService(
      PasswordCredentialRepository credentials,
      EmailVerificationTokenRepository verificationTokens,
      PasswordEncoder passwordEncoder,
      CredentialsProperties properties) {
    this.passwordCredentialRepository = credentials;
    this.verificationTokens = verificationTokens;
    this.passwordEncoder = passwordEncoder;
    this.hashingPermits = new Semaphore(properties.maxConcurrentHashes());
  }

  /** Hashes a password and stores it. One row per account, so calling this twice will conflict. */
  @Transactional
  public void createPassword(UUID userId, String rawPassword) {
    String passwordHash = hash(() -> passwordEncoder.encode(rawPassword));
    passwordCredentialRepository.save(new PasswordCredentialEntity(userId, passwordHash));
  }

  /**
   * Whether a raw password matches the account's stored hash. False for an account with no password
   * at all, which is indistinguishable to the caller from a wrong one.
   *
   * <p>Deliberately not {@code @Transactional}: the comparison can wait on the concurrency gate,
   * and holding a pooled connection for the whole of that wait would trade an out-of-memory risk
   * for a connection-pool exhaustion one. The repository call opens and closes its own transaction,
   * and {@code PasswordCredentialEntity} has no lazy state, so reading its hash afterwards is safe.
   */
  public boolean passwordMatches(UUID userId, String rawPassword) {
    return passwordCredentialRepository
        .findByUserId(userId)
        .map(c -> hash(() -> passwordEncoder.matches(rawPassword, c.getPasswordHash())))
        .orElse(false);
  }

  /**
   * Burns one password hash's worth of time on a fixed, discarded value, so that a branch with
   * nothing to hash costs what a branch that hashes costs.
   *
   * <p>Called from every path through registration and resend that would otherwise return without
   * hashing: an already-registered address, an unknown address, an already-verified account.
   * Argon2id at this application's parameters costs tens of milliseconds against roughly one for
   * the single {@code SELECT} those branches run — a gap trivially measurable over a network, and
   * an account-enumeration oracle if left open. Nothing is written and no real password is touched.
   */
  public void hashDecoyPassword() {
    DECOY_HASH_SINK.set(hash(() -> passwordEncoder.encode(DECOY_PASSWORD)));
  }

  /**
   * Clears every credential an account holds, across both of this domain's tables.
   *
   * <p>One call, so a caller purging an account never has to know that proof material is split
   * across two tables, nor reach for this domain's repositories to clear them. Order between the
   * two does not matter — neither references the other — but both must go before the {@code users}
   * row they share a foreign key with.
   */
  @Transactional
  public void deleteAllForUser(UUID userId) {
    verificationTokens.deleteByUserId(userId);
    passwordCredentialRepository.deleteByUserId(userId);
  }

  /**
   * Runs one hashing operation behind the concurrency gate.
   *
   * <p>Argon2id's 19 MiB working buffer is allocated on the <em>Java heap</em> by BouncyCastle, and
   * Tomcat will happily run its whole thread pool against a ~358 MB heap — roughly 19 concurrent
   * hashes exhaust it and the container is OOM-killed, which is trivially reachable from
   * unauthenticated login traffic. This gate caps what the hash path can hold at any instant to
   * {@code zarlania.credentials.max-concurrent-hashes} buffers; anything beyond that waits instead
   * of allocating, and {@code server.tomcat.threads.max} bounds how many can be waiting at once.
   *
   * <p>Every hash in this class goes through here, the decoy included, and that is not incidental:
   * a decoy that skipped the gate would return immediately while a real hash queued behind it,
   * reopening precisely the timing channel the decoy exists to close.
   */
  private <T> T hash(Supplier<T> hashing) {
    try {
      hashingPermits.acquire();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to hash a password", exception);
    }
    try {
      return hashing.get();
    } finally {
      hashingPermits.release();
    }
  }
}
