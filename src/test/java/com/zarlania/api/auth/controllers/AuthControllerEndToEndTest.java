package com.zarlania.api.auth.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.EndToEndTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What {@code /auth/*} does with a request before any business rule is reached: validation, the
 * error body's shape, and the statuses the framework itself decides.
 *
 * <p>Deliberately holds nothing that needs an account. Anything requiring one belongs in {@link
 * RegistrationFlowTest} or {@link LoginFlowTest}, which is what keeps this class about request and
 * response rather than about behaviour.
 */
@SpringBootTest(
    properties = {
      "zarlania.throttle.endpoints.register.limit=1000",
      "zarlania.throttle.endpoints.verify.limit=1000"
    })
class AuthControllerEndToEndTest extends EndToEndTestBase {

  // Every rejection here has to name the offending field, because a client cannot show a person
  // what to fix from a bare 400.
  @ParameterizedTest(name = "{0} is rejected, naming the field {1}")
  @CsvSource({
    "'{\"email\":\"not-an-address\",\"username\":\"validuser\",\"password\":\"long-enough-pw\"}',"
        + " email",
    "'{\"email\":\"v@example.com\",\"username\":\"\",\"password\":\"long-enough-pw\"}', username",
    "'{\"email\":\"v@example.com\",\"username\":\"validuser\",\"password\":\"short\"}', password"
  })
  void registerRejectsAnInvalidFieldAndSaysWhichOne(String body, String field) throws Exception {
    mockMvc
        .perform(postJson("/auth/register", body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation.failed"))
        .andExpect(jsonPath("$.errors." + field).exists());
  }

  // A blank or absent required field is a client mistake on every one of these routes, and all
  // three answer the same way — which is the property worth pinning, since each is validated by its
  // own record.
  @ParameterizedTest(name = "an empty body to {0}")
  @ValueSource(strings = {"/auth/register", "/auth/verify", "/auth/resend", "/auth/login"})
  void anEmptyJsonObjectIsARejectedRequestRatherThanAServerError(String path) throws Exception {
    mockMvc
        .perform(postJson(path, "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation.failed"));
  }

  // Registration caps a password at 128 characters, so nothing longer can ever be the right answer
  // at login. Without the same cap here, a caller naming a real account makes the service parse and
  // Argon2-hash a body of any size before it can decide anything — work no legitimate client asks
  // for. The rejection is a validation failure, decided from the request alone, so it says nothing
  // about whether the account exists.
  @Test
  void loginRejectsAPasswordLongerThanRegistrationCouldEverHaveStored() throws Exception {
    String overlongPassword = "x".repeat(129);

    mockMvc
        .perform(
            postJson(
                "/auth/login",
                """
                {"identifier":"someone","password":"%s"}
                """
                    .formatted(overlongPassword)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation.failed"))
        .andExpect(jsonPath("$.errors.password").exists());
  }

  // GlobalExceptionHandler extends ResponseEntityExceptionHandler specifically so framework
  // exceptions keep their own status instead of falling into the generic 500 catch-all: a malformed
  // body is a client mistake, not a server failure.
  @Test
  void aMalformedJsonBodyIsARequestErrorRatherThanAServerError() throws Exception {
    mockMvc
        .perform(postJson("/auth/register", "not valid json"))
        .andExpect(status().isBadRequest());
  }

  // Same reasoning, for the wrong verb on a real path.
  @Test
  void theWrongVerbOnARealPathIsMethodNotAllowedRatherThanAServerError() throws Exception {
    mockMvc.perform(get("/auth/register")).andExpect(status().isMethodNotAllowed());
  }

  // An unknown token is indistinguishable from an expired or already-consumed one, on purpose: the
  // response must not say which, or it becomes an oracle for guessing tokens.
  @Test
  void verifyingAnUnknownTokenIsRefusedWithTheInvalidTokenCode() throws Exception {
    mockMvc
        .perform(
            postJson(
                "/auth/verify",
                """
            {"token":"not-a-real-token"}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("auth.invalid-token"));
  }
}
