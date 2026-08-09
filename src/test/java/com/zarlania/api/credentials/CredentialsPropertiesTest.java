package com.zarlania.api.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation this record does as it is bound, which is the only logic it holds.
 *
 * <p>Configuration the domain cannot run on has to stop the process at startup rather than at the
 * first request. A {@code Semaphore} built from a non-positive permit count hands out none, so
 * {@code CredentialsService}'s hashing gate would park the first login thread and never release it
 * — a service that looks hung rather than misconfigured, on the one code path every account needs.
 */
class CredentialsPropertiesTest {

  private static final Duration TTL = Duration.ofHours(24);
  private static final int PERMITS = 4;

  @Test
  void bindsWhenBothPropertiesAreUsable() {
    CredentialsProperties properties = new CredentialsProperties(TTL, PERMITS);

    assertThat(properties.verificationTokenTtl()).isEqualTo(TTL);
    assertThat(properties.maxConcurrentHashes()).isEqualTo(PERMITS);
  }

  // The message has to name the property rather than the record component: whoever reads it is
  // looking at a YAML file or an environment variable, and has no reason to know either name.
  @Test
  void rejectsAMissingVerificationTokenTtlNamingTheProperty() {
    assertThatThrownBy(() -> new CredentialsProperties(null, PERMITS))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("zarlania.credentials.verification-token-ttl");
  }

  // A TTL is added to the current instant to set a token's expiry, so anything not positive issues
  // a token that expired before the email carrying it was sent. That failure is silent in a way the
  // null case is not: rows are written and mail goes out exactly as they do when it works, and only
  // the account owner ever finds out, by being unable to verify and therefore unable to log in.
  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-24H"})
  void rejectsANonPositiveVerificationTokenTtlNamingTheProperty(String ttl) {
    Duration nonPositive = Duration.parse(ttl);

    assertThatThrownBy(() -> new CredentialsProperties(nonPositive, PERMITS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.credentials.verification-token-ttl")
        .hasMessageContaining(nonPositive.toString());
  }

  // Zero is the value that makes this worth checking at all — it is what an unset or misspelled
  // property binds to, and it is indistinguishable from a working service until the first hash.
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsANonPositiveMaxConcurrentHashesNamingTheProperty(int permits) {
    assertThatThrownBy(() -> new CredentialsProperties(TTL, permits))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.credentials.max-concurrent-hashes")
        .hasMessageContaining(String.valueOf(permits));
  }
}
