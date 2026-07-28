package com.zarlania.api.auth.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.testsupport.RecordingEmailSenderConfig;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Covers the per-account half of the throttle: the spec asked for per-IP <em>and</em> per-account
 * limits, and without the second one an attacker with many addresses is unbounded against a single
 * known account.
 *
 * <p>Every per-IP limit is raised out of the way here, and every request below comes from a
 * different forwarded address, so a 429 can only have come from the account bucket. {@link
 * LoginFlowIntegrationTest} is the mirror image, holding the per-IP limits at their defaults.
 */
@SpringBootTest(
    properties = {
      "zarlania.throttle.login-limit=1000",
      "zarlania.throttle.register-limit=1000",
      "zarlania.throttle.resend-limit=1000"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(RecordingEmailSenderConfig.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class AccountThrottleIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  // login-account-limit and resend-account-limit in application.yml; one more request than each is
  // what has to be refused.
  private static final int LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING = 11;
  private static final int RESEND_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING = 4;

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;

  // Each attempt arrives from its own address and spells the identifier differently — leading and
  // trailing space, alternating case. Both are normalized into one bucket key on purpose: email
  // and username are citext columns, so Postgres already treats these spellings as one account,
  // and keying on the raw string would hand an attacker a fresh allowance per spelling.
  @Test
  void loginAttemptsOnOneAccountShareABucketAcrossAddressesAndSpellings() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING; attempt++) {
      loginRequest(addressFor(attempt), spellingFor("targeted-account", attempt))
          .andExpect(status().isUnauthorized());
    }

    loginRequest(
            addressFor(LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING),
            spellingFor("targeted-account", LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // A different account must still have its full allowance while the one above is exhausted —
  // otherwise the bucket is global rather than per-account, which would be a denial of service on
  // every user at once.
  @Test
  void anotherAccountKeepsItsOwnAllowance() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING; attempt++) {
      loginRequest(addressFor(attempt), "exhausted-account").andExpect(status().isUnauthorized());
    }
    loginRequest(addressFor(LOGIN_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING), "exhausted-account")
        .andExpect(status().isTooManyRequests());

    loginRequest(addressFor(1), "untouched-account").andExpect(status().isUnauthorized());
  }

  // Resend is the email-bombing path: without a per-account limit, an attacker with several
  // addresses can keep mailing one victim indefinitely, and every message spends the provider
  // quota the whole service shares.
  @Test
  void resendAttemptsForOneEmailShareABucketAcrossAddresses() throws Exception {
    for (int attempt = 1; attempt < RESEND_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING; attempt++) {
      resendRequest(addressFor(attempt), "bombed@example.com").andExpect(status().isAccepted());
    }

    resendRequest(addressFor(RESEND_ATTEMPTS_TO_TRIGGER_ACCOUNT_THROTTLING), "bombed@example.com")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // TEST-NET-3 (RFC 5737): a distinct address per attempt, so the per-IP bucket is never the
  // reason a request is refused even if its limit were somehow not raised.
  private static String addressFor(int attempt) {
    return "203.0.113." + attempt;
  }

  private static String spellingFor(String identifier, int attempt) {
    return attempt % 2 == 0 ? " " + identifier.toUpperCase(Locale.ROOT) + " " : identifier;
  }

  private ResultActions loginRequest(String forwardedFor, String identifier) throws Exception {
    return mockMvc.perform(
        post("/auth/login")
            .header(FORWARDED_FOR_HEADER, forwardedFor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"identifier":"%s","password":"%s"}
                """
                    .formatted(identifier, PASSWORD)));
  }

  private ResultActions resendRequest(String forwardedFor, String email) throws Exception {
    return mockMvc.perform(
        post("/auth/resend")
            .header(FORWARDED_FOR_HEADER, forwardedFor)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s"}
                """
                    .formatted(email)));
  }
}
