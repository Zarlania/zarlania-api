package com.zarlania.api.email;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.email} configuration block; see {@code application.yml}.
 *
 * @param resendApiKey the provider credential, blank when none is configured — which is a startup
 *     failure in production and a fall back to logging everywhere else
 * @param resendBaseUrl the provider's API root, configurable so a different Resend-compatible
 *     endpoint, or a stub, can be pointed at without a code change
 * @param connectTimeout how long a send may wait for the provider to accept a connection
 * @param readTimeout how long a send may wait for the provider's response once connected. Both
 *     timeouts are bounded because the JDK's HTTP client has no default for either and {@link
 *     #dispatchThreads} is small: one send that waits forever stalls every message behind it.
 * @param dispatchThreads how many sends may be in flight at once
 * @param dispatchQueueCapacity how many sends may wait for a dispatch thread before new ones are
 *     rejected and logged. Bounded so a provider outage cannot grow the queue until the instance is
 *     killed for memory.
 */
@ConfigurationProperties(prefix = "zarlania.email")
public record EmailProperties(
    String from,
    String resendApiKey,
    String resendBaseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int dispatchThreads,
    int dispatchQueueCapacity) {

  /**
   * Rejects configuration outbound mail cannot run on, at startup rather than at the first send.
   *
   * <p>Almost nothing here fails where it is set. A blank {@code from} is a 422 from the provider
   * on every message; a blank {@code resendBaseUrl} leaves each send pointed at a relative path; a
   * non-positive timeout is not "wait forever" but a client that gives up before it starts. Since
   * verification mail is all this channel carries, any of them strands every registration behind it
   * while the service reports itself healthy.
   *
   * <p>{@code resendApiKey} is deliberately not checked. A blank key is how every non-production
   * deployment runs — it selects the logging adapter — and {@link EmailSenderFactory} is the only
   * thing that knows the profile, so whether a missing key is fatal is its decision to make.
   *
   * <p>{@code dispatchQueueCapacity} floors at zero rather than one, because zero is a real choice:
   * it makes the executor hand a send straight to a thread and reject it when none is free, instead
   * of queueing. Negative is the one to catch, since the executor reads anything below one the same
   * way and would silently discard the bound that was meant.
   *
   * <p>Every message names the property as it is written in configuration, not the record component
   * — whoever reads one is looking at {@code application.yml} or an environment variable.
   *
   * @throws NullPointerException if either timeout is not configured
   * @throws IllegalArgumentException if {@code from} or {@code resendBaseUrl} is blank, if either
   *     timeout is not positive, if {@code dispatchThreads} is not at least one, or if {@code
   *     dispatchQueueCapacity} is negative
   */
  public EmailProperties {
    requireNonBlank(from, "zarlania.email.from");
    requireNonBlank(resendBaseUrl, "zarlania.email.resend-base-url");
    requirePositive(connectTimeout, "zarlania.email.connect-timeout");
    requirePositive(readTimeout, "zarlania.email.read-timeout");
    requireAtLeast(dispatchThreads, 1, "zarlania.email.dispatch-threads");
    requireAtLeast(dispatchQueueCapacity, 0, "zarlania.email.dispatch-queue-capacity");
  }

  /**
   * Treats an absent value and an empty one as the same failure, because for a text property they
   * are the same operator mistake: unset binds to null, set-but-empty binds to a blank string, and
   * neither is something a send can use.
   */
  private static void requireNonBlank(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must not be blank");
    }
  }

  private static void requirePositive(Duration value, String property) {
    Objects.requireNonNull(value, property);
    if (!value.isPositive()) {
      throw new IllegalArgumentException(property + " must be positive, but was " + value);
    }
  }

  private static void requireAtLeast(int value, int minimum, String property) {
    if (value < minimum) {
      throw new IllegalArgumentException(
          property + " must be at least " + minimum + ", but was " + value);
    }
  }
}
