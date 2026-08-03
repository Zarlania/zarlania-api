package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.email.EmailDispatcher;
import com.zarlania.api.email.EmailMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What registration puts in each of its two emails.
 *
 * <p>Composition only. That a send never fails the caller and never happens on the request thread
 * are guarantees of the outbound channel, not of this listener, and are covered by {@code
 * EmailDispatcherTest} — this class could not affect them if it tried.
 */
class RegistrationEmailListenerTest {

  private static final String EMAIL = "person@example.com";
  private static final String RAW_TOKEN = "the-raw-token";
  private static final String APP_BASE_URL = "https://zarlania.com";

  private final List<EmailMessage> dispatched = new ArrayList<>();

  // A real dispatcher over a recording sender and a same-thread executor, rather than a mock: it
  // has one public method and nothing worth stubbing, and going through the real one means this
  // test breaks if the two stop fitting together.
  private final EmailDispatcher recordingDispatcher =
      new EmailDispatcher(dispatched::add, Runnable::run);

  @Test
  void theVerificationEmailCarriesALinkBuiltFromTheAppBaseUrlAndTheRawToken() {
    listener().onVerificationEmailRequested(new VerificationEmailRequested(EMAIL, RAW_TOKEN));

    assertThat(dispatched).hasSize(1);
    assertThat(dispatched.getFirst().to()).isEqualTo(EMAIL);
    assertThat(dispatched.getFirst().subject()).isEqualTo("Verify your Zarlania account");
    assertThat(dispatched.getFirst().textBody())
        .contains(APP_BASE_URL + "/verify-email?token=" + RAW_TOKEN);
  }

  // The notice deliberately carries no link and no token: its recipient already has an account, and
  // whoever triggered it may not be them.
  @Test
  void theDuplicateAttemptNoticeGoesToTheExistingOwnerWithoutAnyToken() {
    listener().onDuplicateRegistrationAttempted(new DuplicateRegistrationAttempted(EMAIL));

    assertThat(dispatched).hasSize(1);
    assertThat(dispatched.getFirst().to()).isEqualTo(EMAIL);
    assertThat(dispatched.getFirst().textBody())
        .doesNotContain(RAW_TOKEN)
        .doesNotContain("verify-email");
  }

  private RegistrationEmailListener listener() {
    AuthProperties properties =
        new AuthProperties(
            "https://api.zarlania.com",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            Duration.ofDays(7),
            true,
            "",
            "",
            APP_BASE_URL);
    return new RegistrationEmailListener(recordingDispatcher, properties);
  }
}
