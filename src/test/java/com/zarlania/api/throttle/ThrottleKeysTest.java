package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit-level: the bucket keys are pure string derivation, so nothing here needs a context. */
class ThrottleKeysTest {

  private static final String ENDPOINT = "login";
  private static final int MAX_ACCOUNT_IDENTIFIER_LENGTH = 100;

  @Test
  void aClientKeyIsTheEndpointAndTheAddress() {
    assertThat(ThrottleKeys.forClient(ENDPOINT, "203.0.113.7")).isEqualTo("login:203.0.113.7");
  }

  // The two key shapes share one map, so an address that happened to produce an account key would
  // let a caller consume someone else's bucket. No IP literal can start with "acct:", which is what
  // keeps the two spaces disjoint.
  @Test
  void aClientKeyCanNeverCollideWithAnAccountKey() {
    assertThat(ThrottleKeys.forClient(ENDPOINT, "203.0.113.7"))
        .isNotEqualTo(ThrottleKeys.forAccount(ENDPOINT, "203.0.113.7"));
  }

  @Test
  void differentEndpointsKeepSeparateBucketsForOneAccount() {
    assertThat(ThrottleKeys.forAccount("login", "bob@example.com"))
        .isNotEqualTo(ThrottleKeys.forAccount("resend", "bob@example.com"));
  }

  // email and username are citext columns, so Postgres treats these as one account. Keying on the
  // raw string would hand an attacker a fresh allowance per spelling.
  @ParameterizedTest
  @ValueSource(
      strings = {"bob@example.com", "BOB@example.com", "Bob@Example.COM", "  bob@example.com  "})
  void everySpellingOfOneAccountLandsInOneBucket(String spelling) {
    assertThat(ThrottleKeys.forAccount(ENDPOINT, spelling))
        .isEqualTo(ThrottleKeys.forAccount(ENDPOINT, "bob@example.com"));
  }

  // The identifier field is only @NotBlank, so without truncation a caller could mint arbitrarily
  // long keys in the limiter's map.
  @Test
  void anOverlongIdentifierIsTruncatedSoTheKeyStaysBounded() {
    String key = ThrottleKeys.forAccount(ENDPOINT, "x".repeat(5_000));

    assertThat(key).hasSize(("login:acct:" + "x".repeat(MAX_ACCOUNT_IDENTIFIER_LENGTH)).length());
  }

  // Sharing one bucket between two accounts whose first 100 characters match makes the limit
  // stricter for both, never weaker for either — the safe direction for a truncation to err in.
  @Test
  void twoIdentifiersSharingTheTruncatedPrefixShareABucket() {
    assertThat(ThrottleKeys.forAccount(ENDPOINT, "y".repeat(120) + "a"))
        .isEqualTo(ThrottleKeys.forAccount(ENDPOINT, "y".repeat(120) + "b"));
  }
}
