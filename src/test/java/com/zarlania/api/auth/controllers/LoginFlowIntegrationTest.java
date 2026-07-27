package com.zarlania.api.auth.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.testsupport.RecordingEmailSender;
import com.zarlania.api.testsupport.RecordingEmailSenderConfig;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end coverage of {@code /auth/login}, {@code /auth/refresh} and {@code /auth/logout}
 * against a real database: cookie rotation, family revocation on reuse, and the
 * indistinguishability of "unknown identifier" from "wrong password" can only be trusted by
 * exercising the real stack, not by inspecting the response in isolation.
 *
 * <p>Same harness as {@link RegistrationFlowIntegrationTest}: the Postgres container and the {@link
 * RecordingEmailSender} bean are shared across the whole class, so each test registers its own
 * unique email/username pair rather than relying on transactional rollback between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(RecordingEmailSenderConfig.class)
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class LoginFlowIntegrationTest {

  private static final Pattern VERIFICATION_LINK_PATTERN =
      Pattern.compile("https://zarlania\\.com/verify-email\\?token=([A-Za-z0-9_-]+)");
  private static final String PASSWORD = "correct-horse-battery";
  private static final String REFRESH_COOKIE = "zarlania_refresh";
  private static final String ORG_CLAIM = "org";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;
  private final RecordingEmailSender emailSender;

  @BeforeEach
  void clearRecordedEmails() {
    emailSender.clear();
  }

  @Test
  void loginWithUsernameReturns200WithATokenScopedToThePersonalOrgAndARefreshCookie()
      throws Exception {
    registerAndVerify("frank@example.com", "frank");

    MvcResult result =
        loginRequest("frank", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(
                header()
                    .string(
                        HttpHeaders.SET_COOKIE,
                        Matchers.allOf(
                            Matchers.containsString(REFRESH_COOKIE + "="),
                            Matchers.containsString("Path=/auth"),
                            Matchers.containsString("HttpOnly"),
                            Matchers.containsString("SameSite=Strict"))))
            .andReturn();

    String accessToken = accessToken(result);
    String orgClaim = SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim(ORG_CLAIM);
    meRequest(accessToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organization.id").value(orgClaim));
  }

  @Test
  void loginWithEmailReturns200() throws Exception {
    registerAndVerify("grace@example.com", "grace");

    loginRequest("grace@example.com", PASSWORD).andExpect(status().isOk());
  }

  // Same status, same code, same body — not just the same status and code — is the actual
  // enumeration-safety contract; a body that differs (e.g. a different `detail` string) would
  // still leak which branch was taken even with identical status and code.
  @Test
  void loginWithAWrongPasswordAndAnUnknownIdentifierAreIndistinguishable() throws Exception {
    registerAndVerify("henry@example.com", "henry");

    MvcResult wrongPassword =
        loginRequest("henry", "not-the-right-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth.invalid-credentials"))
            .andReturn();
    MvcResult unknownIdentifier =
        loginRequest("nobody-registered", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth.invalid-credentials"))
            .andReturn();

    assertThat(unknownIdentifier.getResponse().getContentAsString())
        .isEqualTo(wrongPassword.getResponse().getContentAsString());
  }

  @Test
  void loginWithAnUnverifiedAccountReturns403EmailUnverified() throws Exception {
    registerRequest("ivy@example.com", "ivy", PASSWORD).andExpect(status().isAccepted());

    loginRequest("ivy", PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("auth.email-unverified"));
  }

  @Test
  void refreshWithTheCookieReturnsANewAccessTokenAndARotatedCookieValue() throws Exception {
    registerAndVerify("jack@example.com", "jack");
    MvcResult loginResult = loginRequest("jack", PASSWORD).andExpect(status().isOk()).andReturn();
    String oldAccessToken = accessToken(loginResult);
    String oldCookie = refreshCookieValue(loginResult);

    MvcResult refreshResult = refreshRequest(oldCookie).andExpect(status().isOk()).andReturn();

    assertThat(accessToken(refreshResult)).isNotEqualTo(oldAccessToken);
    assertThat(refreshCookieValue(refreshResult)).isNotEqualTo(oldCookie);
  }

  @Test
  void replayingAnOldRefreshCookieRevokesTheWholeFamilySoTheNewOneIsAlsoDead() throws Exception {
    registerAndVerify("kate@example.com", "kate");
    MvcResult loginResult = loginRequest("kate", PASSWORD).andExpect(status().isOk()).andReturn();
    String oldCookie = refreshCookieValue(loginResult);
    MvcResult refreshResult = refreshRequest(oldCookie).andExpect(status().isOk()).andReturn();
    String newCookie = refreshCookieValue(refreshResult);

    refreshRequest(oldCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
    refreshRequest(newCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  @Test
  void refreshWithNoCookieReturns401InvalidCredentials() throws Exception {
    mockMvc
        .perform(post("/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  @Test
  void logoutWithALiveCookieReturns204ClearsItAndKillsAFollowUpRefresh() throws Exception {
    registerAndVerify("leo@example.com", "leo");
    MvcResult loginResult = loginRequest("leo", PASSWORD).andExpect(status().isOk()).andReturn();
    String cookie = refreshCookieValue(loginResult);

    MvcResult logoutResult = logoutRequest(cookie).andExpect(status().isNoContent()).andReturn();

    Cookie cleared = logoutResult.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();

    refreshRequest(cookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  private void registerAndVerify(String email, String username) throws Exception {
    registerRequest(email, username, PASSWORD).andExpect(status().isAccepted());
    String token = extractToken(lastEmailBody());
    verifyRequest(token).andExpect(status().isOk());
    emailSender.clear();
  }

  private String lastEmailBody() {
    return emailSender.messages().get(emailSender.messages().size() - 1).textBody();
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

  private ResultActions loginRequest(String identifier, String password) throws Exception {
    return mockMvc.perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"identifier":"%s","password":"%s"}
                """
                    .formatted(identifier, password)));
  }

  private ResultActions refreshRequest(String cookieValue) throws Exception {
    return mockMvc.perform(post("/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, cookieValue)));
  }

  private ResultActions logoutRequest(String cookieValue) throws Exception {
    return mockMvc.perform(post("/auth/logout").cookie(new Cookie(REFRESH_COOKIE, cookieValue)));
  }

  private ResultActions meRequest(String accessToken) throws Exception {
    return mockMvc.perform(
        get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
  }

  private static String accessToken(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private static String refreshCookieValue(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cookie).isNotNull();
    return cookie.getValue();
  }

  private static String extractToken(String body) {
    Matcher matcher = VERIFICATION_LINK_PATTERN.matcher(body);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
