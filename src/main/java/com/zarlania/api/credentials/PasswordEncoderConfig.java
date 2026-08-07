package com.zarlania.api.credentials;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Supplies the password encoder, with the Argon2id parameters chosen for this deployment.
 *
 * <p>The parameters are the point of this class: memory cost is what makes a hash expensive to
 * attack in bulk, and it is also what {@code CredentialsService}'s permit gate exists to bound,
 * since the buffer is allocated on a heap of roughly 358 MB.
 */
@Configuration
public class PasswordEncoderConfig {

  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19_456; // 19 MiB, OWASP Argon2id baseline
  private static final int ITERATIONS = 2;

  /** Argon2id at this deployment's cost parameters. */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(
        SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  }
}
