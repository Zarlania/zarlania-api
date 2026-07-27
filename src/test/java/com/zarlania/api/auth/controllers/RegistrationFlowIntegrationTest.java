package com.zarlania.api.auth.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.common.email.EmailMessage;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.testsupport.RecordingEmailSender;
import com.zarlania.api.testsupport.RecordingEmailSenderConfig;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end coverage of {@code /auth/register}, {@code /auth/verify} and {@code /auth/resend}
 * against a real database and the real {@code AFTER_COMMIT} email listener — the enumeration-safety
 * contract (same 202 whether or not the email exists) can only be trusted by observing what
 * actually got sent, not by inspecting the response alone.
 *
 * <p>Test data uses one unique email/username pair per method: the Postgres container and its
 * {@link RecordingEmailSender} bean are shared across the whole class, so nothing here relies on
 * transactional rollback between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(RecordingEmailSenderConfig.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RegistrationFlowIntegrationTest {

  private static final Pattern VERIFICATION_LINK_PATTERN =
      Pattern.compile("https://zarlania\\.com/verify-email\\?token=([A-Za-z0-9_-]+)");
  private static final String PASSWORD = "correct-horse-battery";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;
  private final RecordingEmailSender emailSender;

  @BeforeEach
  void clearRecordedEmails() {
    emailSender.clear();
  }

  @Test
  void registerHappyPathAccepts202AndSendsOneVerificationEmailCarryingAToken() throws Exception {
    registerRequest("alice@example.com", "alice", PASSWORD).andExpect(status().isAccepted());

    assertThat(emailSender.messages()).hasSize(1);
    EmailMessage message = emailSender.messages().get(0);
    assertThat(message.to()).isEqualTo("alice@example.com");
    assertThat(message.subject()).isEqualTo("Verify your Zarlania account");
    assertThat(extractToken(message.textBody())).isNotBlank();
  }

  @Test
  void verifyingTheIssuedTokenSucceedsAndARepeatRegistrationSendsTheDuplicateNoticeInstead()
      throws Exception {
    registerRequest("bob@example.com", "bob", PASSWORD).andExpect(status().isAccepted());
    String token = extractToken(emailSender.messages().get(0).textBody());
    emailSender.clear();

    verifyRequest(token).andExpect(status().isOk());

    // Different username: registration checks username before email, so this has to stay
    // available to actually exercise the emailExists branch rather than USERNAME_TAKEN.
    registerRequest("bob@example.com", "bobsecondattempt", PASSWORD)
        .andExpect(status().isAccepted());

    assertThat(emailSender.messages()).hasSize(1);
    assertThat(emailSender.messages().get(0).subject())
        .isEqualTo("Someone tried to register with your email");
  }

  @Test
  void registeringWithATakenUsernameReturns409AndSendsNoEmail() throws Exception {
    registerRequest("carol@example.com", "carolusername", PASSWORD)
        .andExpect(status().isAccepted());
    emailSender.clear();

    registerRequest("someoneelse@example.com", "carolusername", PASSWORD)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("auth.username-taken"));

    assertThat(emailSender.messages()).isEmpty();
  }

  @Test
  void registeringWithATooShortPasswordReturns400WithValidationFailedCode() throws Exception {
    registerRequest("dave@example.com", "daveusername", "short123")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation.failed"))
        .andExpect(jsonPath("$.errors.password").exists());

    assertThat(emailSender.messages()).isEmpty();
  }

  @Test
  void verifyingAGarbageTokenReturns400WithInvalidTokenCode() throws Exception {
    verifyRequest("not-a-real-token")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("auth.invalid-token"));
  }

  @Test
  void resendForAnUnverifiedEmailReturns202AndSendsAFreshToken() throws Exception {
    registerRequest("erin@example.com", "erinusername", PASSWORD).andExpect(status().isAccepted());
    String firstToken = extractToken(emailSender.messages().get(0).textBody());
    emailSender.clear();

    resendRequest("erin@example.com").andExpect(status().isAccepted());

    assertThat(emailSender.messages()).hasSize(1);
    String secondToken = extractToken(emailSender.messages().get(0).textBody());
    assertThat(secondToken).isNotEqualTo(firstToken);
  }

  @Test
  void resendForAnUnknownEmailReturns202AndSendsNoEmail() throws Exception {
    resendRequest("nobody@example.com").andExpect(status().isAccepted());

    assertThat(emailSender.messages()).isEmpty();
  }

  // GlobalExceptionHandler extends ResponseEntityExceptionHandler specifically so framework
  // exceptions like this one keep their own status instead of falling into the generic 500
  // catch-all — a malformed body is a client mistake, not a server failure.
  @Test
  void malformedJsonBodyReturns400NotAGeneric500() throws Exception {
    mockMvc
        .perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not valid json"))
        .andExpect(status().isBadRequest());
  }

  // Same reasoning as the malformed-JSON case above, for HttpRequestMethodNotSupportedException:
  // the wrong verb on a real path is a client mistake (405), not a server failure (500).
  @Test
  void wrongHttpVerbOnRegisterReturns405NotAGeneric500() throws Exception {
    mockMvc.perform(get("/auth/register")).andExpect(status().isMethodNotAllowed());
  }

  private ResultActions registerRequest(String email, String username, String password)
      throws Exception {
    return mockMvc.perform(
        post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s","username":"%s","password":"%s"}
                """
                    .formatted(email, username, password)));
  }

  private ResultActions verifyRequest(String token) throws Exception {
    return mockMvc.perform(
        post("/auth/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"token":"%s"}
                """
                    .formatted(token)));
  }

  private ResultActions resendRequest(String email) throws Exception {
    return mockMvc.perform(
        post("/auth/resend")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s"}
                """
                    .formatted(email)));
  }

  private static String extractToken(String body) {
    Matcher matcher = VERIFICATION_LINK_PATTERN.matcher(body);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
