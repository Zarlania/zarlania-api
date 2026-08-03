package com.zarlania.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every request this service's auth surface accepts, in one place.
 *
 * <p>These bodies were previously written out again in each test that needed them, so a change to
 * any request shape meant finding every copy. Here, a route or a field name changes once.
 *
 * <p>Requests are built from raw JSON rather than serialized objects on purpose: what a client
 * actually puts on the wire is the thing under test, and building it from the same records the
 * server deserializes into would hide a mismatch instead of catching it.
 */
public final class AuthEndpoints {

  /** The refresh cookie's name, as {@code AuthController} sets it. */
  public static final String REFRESH_COOKIE = "zarlania_refresh";

  private static final Pattern VERIFICATION_LINK =
      Pattern.compile("https://zarlania\\.com/verify-email\\?token=([A-Za-z0-9_-]+)");

  private final MockMvc mockMvc;

  /**
   * @param mockMvc the caller's own {@link MockMvc}, so every request goes through the same filter
   *     chain and context the test is configured with
   */
  public AuthEndpoints(MockMvc mockMvc) {
    this.mockMvc = mockMvc;
  }

  /** Reads the access token out of a login or refresh response body. */
  public static String accessTokenOf(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  /** Reads the refresh cookie's value off a response, asserting that one was actually set. */
  public static String refreshCookieOf(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE);
    assertThat(cookie).isNotNull();
    return cookie.getValue();
  }

  /**
   * Pulls the verification token out of an email body, the way a person clicking the link would.
   *
   * <p>Matching the real link rather than reading the token from the database is deliberate: it
   * proves the token reached the user in a usable form, which is the part of verification that can
   * silently break.
   */
  public static String verificationTokenIn(String emailBody) {
    Matcher matcher = VERIFICATION_LINK.matcher(emailBody);
    assertThat(matcher.find()).as("verification link in email body").isTrue();
    return matcher.group(1);
  }

  /** {@code POST /auth/register}. */
  public ResultActions register(String email, String username, String password) throws Exception {
    return mockMvc.perform(
        json(
            post("/auth/register"),
            """
            {"email":"%s","username":"%s","password":"%s"}
            """
                .formatted(email, username, password)));
  }

  /** {@code POST /auth/verify}. */
  public ResultActions verify(String token) throws Exception {
    return mockMvc.perform(
        json(
            post("/auth/verify"),
            """
            {"token":"%s"}
            """
                .formatted(token)));
  }

  /** {@code POST /auth/resend}. */
  public ResultActions resend(String email) throws Exception {
    return mockMvc.perform(
        json(
            post("/auth/resend"),
            """
            {"email":"%s"}
            """
                .formatted(email)));
  }

  /** {@code POST /auth/login}. */
  public ResultActions login(String identifier, String password) throws Exception {
    return mockMvc.perform(
        json(
            post("/auth/login"),
            """
            {"identifier":"%s","password":"%s"}
            """
                .formatted(identifier, password)));
  }

  /**
   * {@code POST /auth/refresh}, carrying a CSRF pair fetched fresh for this request.
   *
   * <p>Refresh and logout authenticate with the refresh cookie rather than a bearer token, which
   * makes them the only two routes guarded by a CSRF token — so a helper that omitted it would
   * exercise a request no real client sends.
   */
  public ResultActions refresh(String cookieValue) throws Exception {
    return mockMvc.perform(
        CsrfCredentials.fetch(mockMvc)
            .applyTo(post("/auth/refresh").cookie(new Cookie(REFRESH_COOKIE, cookieValue))));
  }

  /** {@code POST /auth/refresh} with a CSRF pair but no refresh cookie, which must be refused. */
  public ResultActions refreshWithoutCookie() throws Exception {
    return mockMvc.perform(CsrfCredentials.fetch(mockMvc).applyTo(post("/auth/refresh")));
  }

  /** {@code POST /auth/logout}, carrying both the refresh cookie and a fresh CSRF pair. */
  public ResultActions logout(String cookieValue) throws Exception {
    return mockMvc.perform(
        CsrfCredentials.fetch(mockMvc)
            .applyTo(post("/auth/logout").cookie(new Cookie(REFRESH_COOKIE, cookieValue))));
  }

  /** {@code POST /auth/logout} with no refresh cookie at all, which must still succeed. */
  public ResultActions logoutWithoutCookie() throws Exception {
    return mockMvc.perform(CsrfCredentials.fetch(mockMvc).applyTo(post("/auth/logout")));
  }

  /** {@code GET /users/me} with a bearer token — the check that a minted session actually works. */
  public ResultActions me(String accessToken) throws Exception {
    return mockMvc.perform(
        get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
  }

  private static MockHttpServletRequestBuilder json(
      MockHttpServletRequestBuilder builder, String body) {
    return builder.contentType(MediaType.APPLICATION_JSON).content(body);
  }
}
