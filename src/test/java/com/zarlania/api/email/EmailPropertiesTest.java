package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation this record does as it is bound, which is the only logic it holds.
 *
 * <p>Everything here is a value {@code EmailConfig} and {@code ResendEmailSender} use without
 * re-checking, and most of them fail at the provider rather than at startup: a blank {@code from}
 * is rejected by Resend on every send, and an unbounded timeout holds the single dispatch thread
 * for as long as a hung provider cares to. Verification mail is the only thing this channel
 * carries, so a channel that silently stops working strands every registration behind it.
 */
class EmailPropertiesTest {

  private static final String FROM_ADDRESS = "no-reply@zarlania.com";
  private static final String API_KEY = "re_test_key";
  private static final String BASE_URL = "https://api.resend.com";
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int DISPATCH_THREADS = 1;
  private static final int DISPATCH_QUEUE_CAPACITY = 200;

  @Test
  void bindsWhenEveryPropertyIsUsable() {
    EmailProperties properties = properties();

    assertThat(properties.from()).isEqualTo(FROM_ADDRESS);
    assertThat(properties.resendBaseUrl()).isEqualTo(BASE_URL);
    assertThat(properties.connectTimeout()).isEqualTo(TIMEOUT);
    assertThat(properties.readTimeout()).isEqualTo(TIMEOUT);
    assertThat(properties.dispatchThreads()).isEqualTo(DISPATCH_THREADS);
    assertThat(properties.dispatchQueueCapacity()).isEqualTo(DISPATCH_QUEUE_CAPACITY);
  }

  // Deliberately unvalidated. A blank key is how every non-production deployment runs — it is what
  // selects the logging adapter — and EmailSenderFactory is the one place allowed to decide that a
  // missing key is fatal, because only it knows the profile. Validating it here would take that
  // decision away and break local development.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void acceptsABlankResendApiKeyBecauseTheFactoryOwnsThatDecision(String apiKey) {
    assertThatCode(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    apiKey,
                    BASE_URL,
                    TIMEOUT,
                    TIMEOUT,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .doesNotThrowAnyException();
  }

  // Goes into the Resend request body verbatim, so a blank one is a 422 on every send.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsABlankFromNamingTheProperty(String from) {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    from,
                    API_KEY,
                    BASE_URL,
                    TIMEOUT,
                    TIMEOUT,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.from");
  }

  // The RestClient's root. Blank leaves every send pointed at a relative path and nothing reaches
  // the provider.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsABlankResendBaseUrlNamingTheProperty(String baseUrl) {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    baseUrl,
                    TIMEOUT,
                    TIMEOUT,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.resend-base-url");
  }

  @Test
  void rejectsAMissingConnectTimeoutNamingTheProperty() {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    BASE_URL,
                    null,
                    TIMEOUT,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("zarlania.email.connect-timeout");
  }

  // The JDK's HTTP client has no default for either timeout, and there is one dispatch thread: a
  // non-positive one is not "no limit", it is a client that gives up before it starts.
  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-5S"})
  void rejectsANonPositiveConnectTimeoutNamingTheProperty(String timeout) {
    Duration nonPositive = Duration.parse(timeout);

    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    BASE_URL,
                    nonPositive,
                    TIMEOUT,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.connect-timeout")
        .hasMessageContaining(nonPositive.toString());
  }

  @Test
  void rejectsAMissingReadTimeoutNamingTheProperty() {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    BASE_URL,
                    TIMEOUT,
                    null,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("zarlania.email.read-timeout");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT0S", "PT-10S"})
  void rejectsANonPositiveReadTimeoutNamingTheProperty(String timeout) {
    Duration nonPositive = Duration.parse(timeout);

    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    BASE_URL,
                    TIMEOUT,
                    nonPositive,
                    DISPATCH_THREADS,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.read-timeout")
        .hasMessageContaining(nonPositive.toString());
  }

  // Becomes the executor's core and max pool size. Zero or less means no thread ever runs a send.
  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void rejectsANonPositiveDispatchThreadsNamingTheProperty(int threads) {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS,
                    API_KEY,
                    BASE_URL,
                    TIMEOUT,
                    TIMEOUT,
                    threads,
                    DISPATCH_QUEUE_CAPACITY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.dispatch-threads")
        .hasMessageContaining(String.valueOf(threads));
  }

  // Zero is a real choice — it makes the executor hand off directly and reject a send the moment
  // every thread is busy, rather than queueing it — so the floor here is zero, not one.
  @Test
  void acceptsAZeroDispatchQueueCapacityBecauseThatMeansDirectHandoff() {
    EmailProperties properties =
        new EmailProperties(FROM_ADDRESS, API_KEY, BASE_URL, TIMEOUT, TIMEOUT, DISPATCH_THREADS, 0);

    assertThat(properties.dispatchQueueCapacity()).isZero();
  }

  // Negative is not a smaller queue, and it is not rejected downstream either: the executor reads
  // anything below one as direct handoff, so a typo here would silently discard the bound the
  // operator meant to set.
  @Test
  void rejectsANegativeDispatchQueueCapacityNamingTheProperty() {
    assertThatThrownBy(
            () ->
                new EmailProperties(
                    FROM_ADDRESS, API_KEY, BASE_URL, TIMEOUT, TIMEOUT, DISPATCH_THREADS, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.email.dispatch-queue-capacity")
        .hasMessageContaining("-1");
  }

  private static EmailProperties properties() {
    return new EmailProperties(
        FROM_ADDRESS,
        API_KEY,
        BASE_URL,
        TIMEOUT,
        TIMEOUT,
        DISPATCH_THREADS,
        DISPATCH_QUEUE_CAPACITY);
  }
}
