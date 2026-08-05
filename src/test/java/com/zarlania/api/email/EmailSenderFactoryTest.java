package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

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

  private static EmailSenderFactory factory(String apiKey, MockEnvironment environment) {
    return new EmailSenderFactory(apiKey, FROM_ADDRESS, BASE_URL, environment);
  }
}
