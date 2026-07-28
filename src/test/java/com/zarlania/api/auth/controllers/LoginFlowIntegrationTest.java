package com.zarlania.api.auth.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.testsupport.RecordingEmailSender;
import com.zarlania.api.testsupport.RecordingEmailSenderConfig;
import jakarta.servlet.http.Cookie;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.assertj.core.data.Offset;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
// register-limit is raised: every test method registers its own account under one shared
// InMemoryRateLimiter for the class's lifetime, more setup calls than 5/min allows. login-limit
// stays at the production default (10/min) because the throttle tests below exercise it directly;
// each uses its own client identity (remote address or X-Forwarded-For) so its count can't combine
// with another test method's login calls and shift the trigger point.
// login-account-limit is raised for the same reason from the other direction: the per-account
// bucket would otherwise trip at the same request count as the per-IP one, leaving every 429 below
// ambiguous about which limit produced it. AccountThrottleIntegrationTest is its mirror image —
// per-IP limits raised, per-account limits at their defaults.
@SpringBootTest(
    properties = {
      "zarlania.throttle.register-limit=1000",
      "zarlania.throttle.login-account-limit=1000"
    })
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
  private static final String THROTTLE_TEST_REMOTE_ADDR = "203.0.113.7";
  private static final int LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING = 11;
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  // TEST-NET-2 (RFC 5737); distinct per test so no method's bucket state bleeds into another.
  private static final String FORWARDED_ADDR_SHARED_BUCKET_TEST = "198.51.100.10";
  private static final String FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY = "198.51.100.30";
  private static final String FORWARDED_ADDR_INDEPENDENCE_TEST_SECONDARY = "198.51.100.40";
  private static final String FORWARDED_ADDR_SPOOFING_TEST = "198.51.100.50";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;
  private final RecordingEmailSender emailSender;
  private final AuthProperties authProperties;

  @BeforeEach
  void clearRecordedEmails() {
    emailSender.clear();
  }

  @Test
  void loginWithUsernameReturns200WithATokenScopedToThePersonalOrgAndARefreshCookie()
      throws Exception {
    // No AUTH_COOKIE_SECURE override anywhere in the test configuration, so this resolves to
    // application.yml's default (true). Asserted explicitly, not assumed, since the Set-Cookie
    // assertion below only makes sense as a check on the real thing if this really is true here.
    assertThat(authProperties.cookieSecure()).isTrue();
    registerAndVerify("frank@example.com", "frank");

    MvcResult result =
        loginRequest("frank", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, mirroredCookieAttributes()))
            .andReturn();

    // The brief's contract: Max-Age is seconds from now until familyExpiresAt, i.e. approximately
    // AuthProperties.refreshFamilyLifetime() (P30D). A tolerance of a few seconds absorbs the gap
    // between RefreshTokenService computing familyExpiresAt and this assertion running.
    Cookie liveCookie = result.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(liveCookie).isNotNull();
    assertThat(liveCookie.getMaxAge())
        .isCloseTo((int) authProperties.refreshFamilyLifetime().toSeconds(), Offset.offset(5));

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

  // Uses its own remote address (see the class-level comment) so its 11 requests form a bucket
  // no other test method's login calls can land in, regardless of JUnit's execution order —
  // login-limit is left at the production default (10/min) specifically so this exercises the
  // real limit, not a raised test-only one.
  @Test
  void theEleventhRapidLoginWithAWrongPasswordIsThrottled() throws Exception {
    registerAndVerify("mia@example.com", "mia");

    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestFrom(THROTTLE_TEST_REMOTE_ADDR, "mia", "not-the-right-password")
          .andExpect(status().isUnauthorized());
    }

    loginRequestFrom(THROTTLE_TEST_REMOTE_ADDR, "mia", "not-the-right-password")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // ClientIpResolver reads X-Forwarded-For so a caller resolves to itself rather than to Render's
  // proxy, which would otherwise put every user in one bucket. Unknown identifier: purely about
  // throttling.
  @Test
  void requestsSharingAForwardedForAddressShareAThrottleBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(FORWARDED_ADDR_SHARED_BUCKET_TEST, "nobody-shared", PASSWORD)
          .andExpect(status().isUnauthorized());
    }

    loginRequestForwardedFrom(FORWARDED_ADDR_SHARED_BUCKET_TEST, "nobody-shared", PASSWORD)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // Drives the primary address past its limit rather than only up to it: an assertion made while
  // the primary bucket still had capacity would pass even if the two addresses shared one bucket.
  @Test
  void requestsWithDifferentForwardedForAddressesGetIndependentThrottleBuckets() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(
              FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY, "nobody-independent", PASSWORD)
          .andExpect(status().isUnauthorized());
    }
    loginRequestForwardedFrom(
            FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY, "nobody-independent", PASSWORD)
        .andExpect(status().isTooManyRequests());

    // Primary is exhausted; a different forwarded address must still have its full allowance.
    loginRequestForwardedFrom(
            FORWARDED_ADDR_INDEPENDENCE_TEST_SECONDARY, "nobody-independent", PASSWORD)
        .andExpect(status().isUnauthorized());
  }

  // The bypass. A proxy appends to X-Forwarded-For rather than replacing it, so everything left of
  // the last entry is attacker-supplied; every request below presents a *different* forged
  // leftmost entry and the same proxy-appended rightmost one. Keying on the leftmost entry — which
  // is what server.forward-headers-strategy: framework does — would give each request a fresh
  // bucket and never throttle at all.
  @Test
  void aRotatingForgedLeftmostForwardedForEntryStillLandsInTheRealCallersBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(
              forgedLeftmostFrom(attempt) + ", " + FORWARDED_ADDR_SPOOFING_TEST,
              "nobody-spoofed",
              PASSWORD)
          .andExpect(status().isUnauthorized());
    }

    loginRequestForwardedFrom(
            forgedLeftmostFrom(LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING)
                + ", "
                + FORWARDED_ADDR_SPOOFING_TEST,
            "nobody-spoofed",
            PASSWORD)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  private static String forgedLeftmostFrom(int attempt) {
    return "192.0.2." + attempt;
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

    // The cleared cookie must mirror the live cookie's HttpOnly/Secure/SameSite/Path attributes —
    // a specific ruling made because the brief's own sample code omitted them on clear. Checked
    // via the same matcher the live-cookie test uses, so a future asymmetry regression (e.g.
    // someone hand-rolling a cleared cookie without AuthController's shared buildRefreshCookie
    // helper) fails here rather than only being caught by manual review.
    MvcResult logoutResult =
        logoutRequest(cookie)
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, mirroredCookieAttributes()))
            .andReturn();

    // Max-Age is checked via the parsed Cookie, not by string-matching the header, since
    // "Max-Age=0" could coincidentally substring-match other header content.
    Cookie cleared = logoutResult.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();

    refreshRequest(cookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  // The brief is explicit: "a missing cookie on logout is not an error." AuthController's
  // `if (cookie != null)` guard exists for exactly this case — a client that already lost its
  // cookie, or never had one, must still get a clean 204 with a cleared cookie, not a 401.
  @Test
  void logoutWithNoCookieReturns204AndStillSetsAClearedCookie() throws Exception {
    MvcResult result =
        mockMvc.perform(post("/auth/logout")).andExpect(status().isNoContent()).andReturn();

    Cookie cleared = result.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();
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
    return performLogin(identifier, password, UnaryOperator.identity());
  }

  // Only the direct-IP throttling test needs a fixed remote address; every other login call in
  // this class keeps MockMvc's default.
  private ResultActions loginRequestFrom(String remoteAddr, String identifier, String password)
      throws Exception {
    return performLogin(
        identifier,
        password,
        builder ->
            builder.with(
                request -> {
                  request.setRemoteAddr(remoteAddr);
                  return request;
                }));
  }

  // Sends the header ClientIpResolver actually reads, rather than bypassing it the way
  // loginRequestFrom's setRemoteAddr does. Takes the whole header value, not one address, so a
  // test can present the multi-entry form a real proxy produces.
  private ResultActions loginRequestForwardedFrom(
      String forwardedFor, String identifier, String password) throws Exception {
    return performLogin(
        identifier, password, builder -> builder.header(FORWARDED_FOR_HEADER, forwardedFor));
  }

  private ResultActions performLogin(
      String identifier, String password, UnaryOperator<MockHttpServletRequestBuilder> customize)
      throws Exception {
    return mockMvc.perform(
        customize.apply(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"identifier":"%s","password":"%s"}
                    """
                        .formatted(identifier, password))));
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

  // Shared by the live-cookie and cleared-cookie assertions so both check the exact same
  // attribute set — Secure is asserted unconditionally because cookieSecure() is asserted true
  // above, not because it is hardcoded as an assumption.
  private static org.hamcrest.Matcher<String> mirroredCookieAttributes() {
    return Matchers.allOf(
        Matchers.containsString(REFRESH_COOKIE + "="),
        Matchers.containsString("Path=/auth"),
        Matchers.containsString("HttpOnly"),
        Matchers.containsString("Secure"),
        Matchers.containsString("SameSite=Strict"));
  }

  private static String extractToken(String body) {
    Matcher matcher = VERIFICATION_LINK_PATTERN.matcher(body);
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }
}
