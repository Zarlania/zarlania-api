package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation this record does as it is bound, which is the only logic it holds beyond exposing
 * the optional bucket.
 *
 * <p>A limit of zero or less is not a stricter throttle, it is a broken one: {@code
 * InMemoryRateLimiter} permits a request when the running count is at or below the limit, so the
 * first caller of the window already exceeds it and every request to the endpoint is refused. An
 * endpoint configured that way is closed rather than protected, which no reading of the
 * configuration would suggest.
 */
class EndpointLimitsTest {

  private static final int LIMIT = 10;
  private static final int ACCOUNT_LIMIT = 5;

  @Test
  void bindsAnEndpointWithNoAccountBucket() {
    EndpointLimits limits = new EndpointLimits(LIMIT, null);

    assertThat(limits.limit()).isEqualTo(LIMIT);
    assertThat(limits.accountLimitIfPresent()).isEmpty();
  }

  @Test
  void bindsAnEndpointWithAnAccountBucket() {
    EndpointLimits limits = new EndpointLimits(LIMIT, ACCOUNT_LIMIT);

    assertThat(limits.limit()).isEqualTo(LIMIT);
    assertThat(limits.accountLimitIfPresent()).contains(ACCOUNT_LIMIT);
  }

  // Zero is what a key left empty in the YAML binds to, and it is the value that turns a throttle
  // into a closed door rather than a loose one.
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsANonPositiveLimit(int limit) {
    assertThatThrownBy(() -> new EndpointLimits(limit, ACCOUNT_LIMIT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit")
        .hasMessageContaining(String.valueOf(limit));
  }

  // Absent and non-positive are different things here: absent means the endpoint has no account
  // bucket, which is a real configuration several endpoints use. Zero means it has one that
  // refuses everybody, which is not.
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsANonPositiveAccountLimit(int accountLimit) {
    assertThatThrownBy(() -> new EndpointLimits(LIMIT, accountLimit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account-limit")
        .hasMessageContaining(String.valueOf(accountLimit));
  }
}
