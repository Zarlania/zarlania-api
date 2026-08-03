package com.zarlania.api.auth.controllers;

import static com.zarlania.api.testsupport.AuthEndpoints.accessTokenOf;
import static com.zarlania.api.testsupport.AuthEndpoints.refreshCookieOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.testsupport.AuthEndpoints;
import com.zarlania.api.testsupport.FlowTestBase;
import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import org.assertj.core.data.Offset;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The session lifecycle as a client lives it: log in, refresh, and log out, each step carrying
 * state into the next.
 *
 * <p>A flow test rather than a set of endpoint tests, because the properties that matter here exist
 * only across requests. That refresh rotates the cookie is meaningful only against the cookie login
 * issued; that replaying an old cookie kills the whole family can be seen only by using the new one
 * afterwards; that logout ends a session can be seen only by refreshing after it.
 *
 * <p>Every test seeds its own account under a unique slug. The container and the rate limiter are
 * shared for the run, so nothing here may assume an empty database or an untouched bucket.
 */
// Both raised limits are about setup, not about what this class asserts: every test method
// registers, verifies and logs in its own account under one shared InMemoryRateLimiter, which is
// more calls than 5 registrations and 10 logins per account a minute allow. The throttle itself is
// covered by ClientIpThrottleEndToEndTest and AccountThrottleEndToEndTest against real limits.
@SpringBootTest(
    properties = {
      "zarlania.throttle.endpoints.register.limit=1000",
      "zarlania.throttle.endpoints.login.account-limit=1000"
    })
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class LoginFlowTest extends FlowTestBase {

  private static final String ORG_CLAIM = "org";
  private static final int COOKIE_MAX_AGE_TOLERANCE_SECONDS = 5;

  private final AuthProperties authProperties;

  @Test
  void loginReturnsATokenScopedToThePersonalOrganizationAndARefreshCookie() throws Exception {
    // No AUTH_COOKIE_SECURE override anywhere in the test configuration, so this resolves to
    // application.yml's default (true). Asserted explicitly, not assumed, since the Set-Cookie
    // assertion below only makes sense as a check on the real thing if this really is true here.
    assertThat(authProperties.cookieSecure()).isTrue();
    registerAndVerify("frank@example.com", "frank");

    MvcResult result =
        auth.login("frank", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, mirroredCookieAttributes()))
            .andReturn();

    // Max-Age is seconds from now until familyExpiresAt, i.e. approximately
    // AuthProperties.refreshFamilyLifetime(). A tolerance of a few seconds absorbs the gap between
    // RefreshTokenService computing familyExpiresAt and this assertion running.
    Cookie liveCookie = result.getResponse().getCookie(AuthEndpoints.REFRESH_COOKIE);
    assertThat(liveCookie).isNotNull();
    assertThat(liveCookie.getMaxAge())
        .isCloseTo(
            (int) authProperties.refreshFamilyLifetime().toSeconds(),
            Offset.offset(COOKIE_MAX_AGE_TOLERANCE_SECONDS));

    String accessToken = accessTokenOf(result);
    String orgClaim = SignedJWT.parse(accessToken).getJWTClaimsSet().getStringClaim(ORG_CLAIM);
    auth.me(accessToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organization.id").value(orgClaim));
  }

  // Both columns are unique and citext, so either identifier in any spelling reaches the same
  // account. Driven from one data provider rather than three near-identical tests, since the only
  // thing that differs is the string presented. Each case seeds its own account, because the
  // database is shared for the run and a second registration of one account is a 409.
  @ParameterizedTest(name = "logging in with {1}")
  @CsvSource({
    "gracename, gracename",
    "graceemail, graceemail@example.com",
    "gracecase, GRACECASE@EXAMPLE.COM"
  })
  void loginAcceptsEitherIdentifierInAnySpelling(String slug, String identifier) throws Exception {
    registerAndVerify(slug + "@example.com", slug);

    auth.login(identifier, PASSWORD).andExpect(status().isOk());
  }

  // Same status, same code, same body — not just the same status and code — is the actual
  // enumeration-safety contract; a body that differs (a different `detail` string, say) would still
  // leak which branch was taken even with identical status and code.
  @Test
  void loginWithAWrongPasswordAndAnUnknownIdentifierAreIndistinguishable() throws Exception {
    registerAndVerify("henry@example.com", "henry");

    MvcResult wrongPassword =
        auth.login("henry", "not-the-right-password")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth.invalid-credentials"))
            .andReturn();
    MvcResult unknownIdentifier =
        auth.login("nobody-registered", PASSWORD)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth.invalid-credentials"))
            .andReturn();

    assertThat(unknownIdentifier.getResponse().getContentAsString())
        .isEqualTo(wrongPassword.getResponse().getContentAsString());
  }

  @Test
  void loginWithAnUnverifiedAccountIsRefusedAsUnverifiedRatherThanAsBadCredentials()
      throws Exception {
    auth.register("ivy@example.com", "ivy", PASSWORD).andExpect(status().isAccepted());

    auth.login("ivy", PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("auth.email-unverified"));
  }

  @Test
  void refreshReturnsANewAccessTokenAndRotatesTheCookie() throws Exception {
    registerAndVerify("jack@example.com", "jack");
    MvcResult login = auth.login("jack", PASSWORD).andExpect(status().isOk()).andReturn();

    MvcResult refreshed =
        auth.refresh(refreshCookieOf(login)).andExpect(status().isOk()).andReturn();

    assertThat(accessTokenOf(refreshed)).isNotEqualTo(accessTokenOf(login));
    assertThat(refreshCookieOf(refreshed)).isNotEqualTo(refreshCookieOf(login));
  }

  // Reuse is the theft signal: whoever presents an already-redeemed token is either the thief or
  // the victim, and there is no way to tell which — so both are cut off.
  @Test
  void replayingAnOldRefreshCookieRevokesTheWholeFamilySoTheNewOneIsAlsoDead() throws Exception {
    registerAndVerify("kate@example.com", "kate");
    MvcResult login = auth.login("kate", PASSWORD).andExpect(status().isOk()).andReturn();
    String oldCookie = refreshCookieOf(login);
    String newCookie =
        refreshCookieOf(auth.refresh(oldCookie).andExpect(status().isOk()).andReturn());

    auth.refresh(oldCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
    auth.refresh(newCookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  @Test
  void refreshWithNoCookieIsRefused() throws Exception {
    auth.refreshWithoutCookie()
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  @Test
  void logoutClearsTheCookieAndKillsAFollowUpRefresh() throws Exception {
    registerAndVerify("leo@example.com", "leo");
    MvcResult login = auth.login("leo", PASSWORD).andExpect(status().isOk()).andReturn();
    String cookie = refreshCookieOf(login);

    // The cleared cookie must mirror the live cookie's HttpOnly/Secure/SameSite/Path attributes.
    // Checked with the same matcher the live-cookie test uses, so an asymmetry regression — someone
    // hand-rolling a cleared cookie instead of going through AuthController's shared builder —
    // fails here rather than being left to review.
    MvcResult logout =
        auth.logout(cookie)
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, mirroredCookieAttributes()))
            .andReturn();
    assertClearedCookie(logout);

    auth.refresh(cookie)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("auth.invalid-credentials"));
  }

  // A missing cookie on logout is not an error: a client that already lost its cookie, or never had
  // one, must still get a clean 204 with a cleared cookie rather than a 401.
  @Test
  void logoutWithNoCookieStillSucceedsAndStillClearsTheCookie() throws Exception {
    MvcResult result = auth.logoutWithoutCookie().andExpect(status().isNoContent()).andReturn();

    assertClearedCookie(result);
  }

  // Max-Age via the parsed Cookie rather than by string-matching the header, since "Max-Age=0"
  // could coincidentally substring-match other header content.
  private static void assertClearedCookie(MvcResult result) {
    Cookie cleared = result.getResponse().getCookie(AuthEndpoints.REFRESH_COOKIE);
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();
  }

  // Shared by the live-cookie and cleared-cookie assertions so both check the exact same attribute
  // set. Secure is asserted unconditionally because cookieSecure() is asserted true above, not
  // because it is hardcoded as an assumption.
  private static Matcher<String> mirroredCookieAttributes() {
    return Matchers.allOf(
        Matchers.containsString(AuthEndpoints.REFRESH_COOKIE + "="),
        Matchers.containsString("Path=/auth"),
        Matchers.containsString("HttpOnly"),
        Matchers.containsString("Secure"),
        Matchers.containsString("SameSite=Strict"));
  }
}
