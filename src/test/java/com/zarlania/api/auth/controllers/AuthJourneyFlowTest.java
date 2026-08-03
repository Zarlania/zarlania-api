package com.zarlania.api.auth.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zarlania.api.testsupport.CsrfCredentials;
import com.zarlania.api.testsupport.FlowTestBase;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Walks the whole registration-to-logout story in one ordered sequence, carrying state from each
 * step to the next, the way a real user actually lives through it. {@link
 * com.zarlania.api.auth.controllers.RegistrationFlowTest} and {@link
 * com.zarlania.api.auth.controllers.LoginFlowTest} already prove each endpoint in isolation; this
 * class exists to catch an integration break that every one of those still passes — in particular,
 * that reuse detection genuinely revokes the whole refresh-token family (not just the replayed
 * token) and that logout genuinely kills the family (not just the browser's cookie).
 *
 * <p>One test method, deliberately: splitting it into independent methods that each re-register a
 * user would lose the ordering and the carried state that is the entire point of a journey test.
 *
 * <p>This class makes one registration and three logins across the whole method — far below the
 * production register-limit (5/min) and login-limit (10/min) in {@code application.yml} — so,
 * unlike {@code LoginFlowTest}, no throttle property needs raising here.
 */
class AuthJourneyFlowTest extends FlowTestBase {

  private static final Pattern VERIFICATION_LINK_PATTERN =
      Pattern.compile("https://zarlania\\.com/verify-email\\?token=([A-Za-z0-9_-]+)");
  private static final String PASSWORD = "correct-horse-battery";
  private static final String EMAIL = "journey@example.com";
  private static final String USERNAME = "journeyuser";
  private static final String REFRESH_COOKIE = "zarlania_refresh";

  @Test
  void walksTheFullRegistrationToLogoutJourneyCarryingStateAtEachStep() throws Exception {
    register().andExpect(status().isAccepted());
    String verificationToken = extractToken(recordedEmails.messages().get(0).textBody());

    login(USERNAME, PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("auth.email-unverified"));

    verify(verificationToken).andExpect(status().isOk());

    MvcResult loginResult = login(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn();
    String accessToken = accessToken(loginResult);
    String firstCookie = refreshCookieValue(loginResult);

    me(accessToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value(USERNAME))
        .andExpect(jsonPath("$.organization.type").value("PERSONAL"));

    MvcResult refreshResult = refresh(firstCookie).andExpect(status().isOk()).andReturn();
    String secondCookie = refreshCookieValue(refreshResult);
    assertThat(secondCookie).isNotEqualTo(firstCookie);

    // Replaying the now-stale first cookie must not just fail on its own — it has to revoke the
    // whole family, or reuse detection isn't doing anything a simple "already used" check wouldn't.
    refresh(firstCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
    // The second cookie was never replayed by an attacker; it dies anyway, proving the family —
    // not just the replayed token — was revoked.
    refresh(secondCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));

    MvcResult freshLoginResult = login(USERNAME, PASSWORD).andExpect(status().isOk()).andReturn();
    String freshCookie = refreshCookieValue(freshLoginResult);

    logout(freshCookie).andExpect(status().isNoContent());

    // Proves logout killed the family server-side rather than merely clearing the browser's
    // cookie: the very cookie logout was called with is now rejected too.
    refresh(freshCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  private ResultActions register() throws Exception {
    return mockMvc.perform(
        post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"%s","username":"%s","password":"%s"}
                """
                    .formatted(EMAIL, USERNAME, PASSWORD)));
  }

  private ResultActions verify(String token) throws Exception {
    return mockMvc.perform(
        post("/auth/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"token":"%s"}
                """
                    .formatted(token)));
  }

  private ResultActions login(String identifier, String password) throws Exception {
    return mockMvc.perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"identifier":"%s","password":"%s"}
                """
                    .formatted(identifier, password)));
  }

  // The journey includes fetching a CSRF token, because a real client's journey does: these are the
  // two routes that authenticate with the refresh cookie, and SecurityConfig guards both.
  private ResultActions refresh(String cookieValue) throws Exception {
    return mockMvc.perform(
        CsrfCredentials.fetch(mockMvc)
            .applyTo(post("/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, cookieValue))));
  }

  private ResultActions logout(String cookieValue) throws Exception {
    return mockMvc.perform(
        CsrfCredentials.fetch(mockMvc)
            .applyTo(post("/auth/logout").cookie(new Cookie(REFRESH_COOKIE, cookieValue))));
  }

  private ResultActions me(String accessToken) throws Exception {
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
