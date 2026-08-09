package com.zarlania.api.credentials;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.credentials} configuration block; see {@code application.yml}.
 *
 * <p>Owned by this domain rather than read from {@code AuthProperties}: {@code auth} composes
 * {@code credentials} through its services, so a {@code credentials} class binding an {@code auth}
 * properties record would invert that dependency and stop this domain being liftable on its own.
 */
@ConfigurationProperties(prefix = "zarlania.credentials")
public record CredentialsProperties(Duration verificationTokenTtl, int maxConcurrentHashes) {

  /**
   * Rejects configuration this domain cannot run on, at startup rather than at the first request.
   *
   * <p>{@code verificationTokenTtl} is added to the current instant to set a verification token's
   * expiry, so a zero or negative one issues tokens that are already expired when the email
   * carrying them is sent. Nobody could ever verify an address, and since an unverified account
   * cannot log in, every registration would strand its owner — with the emails going out and the
   * rows being written exactly as they do when it works.
   *
   * <p>{@code maxConcurrentHashes} sizes {@code CredentialsService}'s hashing gate, and a {@code
   * Semaphore} built from a non-positive permit count hands out none: the first thread to hash a
   * password would park and never be released, so registration and login would hang rather than
   * fail. Zero is what an unset or misspelled property binds to, which is exactly the case worth
   * catching here.
   *
   * <p>Every message names the property as it is written in configuration, not the record component
   * — whoever reads one is looking at {@code application.yml} or an environment variable.
   *
   * @throws NullPointerException if {@code verificationTokenTtl} is not configured
   * @throws IllegalArgumentException if {@code verificationTokenTtl} is not positive, or if {@code
   *     maxConcurrentHashes} is not at least one
   */
  public CredentialsProperties {
    Objects.requireNonNull(verificationTokenTtl, "zarlania.credentials.verification-token-ttl");
    if (!verificationTokenTtl.isPositive()) {
      throw new IllegalArgumentException(
          "zarlania.credentials.verification-token-ttl must be positive, but was "
              + verificationTokenTtl);
    }
    if (maxConcurrentHashes < 1) {
      throw new IllegalArgumentException(
          "zarlania.credentials.max-concurrent-hashes must be at least 1, but was "
              + maxConcurrentHashes);
    }
  }
}
