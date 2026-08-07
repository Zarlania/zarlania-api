package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;

/**
 * Which adapter a deployment sends through, and when a missing key is allowed to be survivable.
 *
 * <p>Both decisions are silent when wrong, which is why they are pinned here. A deployment that
 * quietly logged its verification emails instead of sending them would look healthy while stranding
 * every new account.
 */
class EmailSenderFactoryTest {

  private static final String FROM_ADDRESS = "no-reply@zarlania.com";
  private static final String BASE_URL = "https://api.resend.com";
  private static final String API_KEY = "re_test_key";
  private static final String PRODUCTION_PROFILE = "production";
  private static final Duration TIMEOUT = Duration.ofMillis(250);
  // Comfortably above the timeout above, so the test fails on a client that never gives up rather
  // than on a slow machine.
  private static final int STALLED_PROVIDER_TEST_TIMEOUT_SECONDS = 20;
  // Irrelevant to every assertion here, but EmailProperties binds the whole block.
  private static final int DISPATCH_THREADS = 1;
  private static final int DISPATCH_QUEUE_CAPACITY = 200;

  @Test
  void aBlankKeyOutsideProductionSelectsTheLoggingAdapter() {
    assertThat(factory("", new MockEnvironment()).create()).isInstanceOf(LoggingEmailSender.class);
  }

  @Test
  void aBlankKeyInProductionFailsStartupRatherThanFallingBackToLogging() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(PRODUCTION_PROFILE);

    assertThatThrownBy(() -> factory("", environment).create())
        .isInstanceOf(IllegalStateException.class);
  }

  // A key that is present but empty of content is the same as no key at all — a deployment that set
  // the variable to whitespace must not get a client that authenticates with it.
  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void aKeyOfOnlyWhitespaceCountsAsUnconfigured(String apiKey) {
    assertThat(factory(apiKey, new MockEnvironment()).create())
        .isInstanceOf(LoggingEmailSender.class);
  }

  @Test
  void aConfiguredKeySelectsTheRealProvider() {
    assertThat(factory(API_KEY, new MockEnvironment()).create())
        .isInstanceOf(ResendEmailSender.class);
  }

  @Test
  void aConfiguredKeyInProductionSelectsTheRealProvider() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(PRODUCTION_PROFILE);

    assertThat(factory(API_KEY, environment).create()).isInstanceOf(ResendEmailSender.class);
  }

  // A provider that accepts the connection and then never answers is the failure this guards: the
  // JDK's HTTP client waits forever by default, and EmailConfig dispatches on a single thread, so
  // one such send would stall every queued verification email until the process restarted. The
  // stub server below never writes a byte, so a client without a read timeout would hang here
  // until JUnit's own timeout killed it.
  @Test
  @Timeout(STALLED_PROVIDER_TEST_TIMEOUT_SECONDS)
  void aProviderThatAcceptsAConnectionAndNeverAnswersGivesUpRatherThanHanging() throws Exception {
    try (ServerSocket silentProvider = new ServerSocket(0)) {
      EmailSender sender =
          new EmailSenderFactory(
                  properties(API_KEY, "http://localhost:" + silentProvider.getLocalPort()),
                  new MockEnvironment())
              .create();

      assertThatThrownBy(() -> sender.send(new EmailMessage("to@example.com", "s", "b", "ref")))
          .isInstanceOf(ResourceAccessException.class);
    }
  }

  private static EmailSenderFactory factory(String apiKey, MockEnvironment environment) {
    return new EmailSenderFactory(properties(apiKey, BASE_URL), environment);
  }

  private static EmailProperties properties(String apiKey, String baseUrl) {
    return new EmailProperties(
        FROM_ADDRESS, apiKey, baseUrl, TIMEOUT, TIMEOUT, DISPATCH_THREADS, DISPATCH_QUEUE_CAPACITY);
  }
}
