package com.zarlania.api.auth.controllers;

import static com.zarlania.api.testsupport.AuthEndpoints.refreshCookieOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.AuthEndpoints;
import com.zarlania.api.testsupport.CsrfCredentials;
import com.zarlania.api.testsupport.FlowTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The CSRF filter as a request meets it, on the only two routes that have a CSRF surface at all.
 *
 * <p>Middleware, not endpoint behaviour: every rejection here happens before the controller runs,
 * which is exactly why it belongs in its own end-to-end test rather than among the session flow's
 * assertions. {@code /auth/refresh} and {@code /auth/logout} authenticate with a cookie the browser
 * attaches by itself, so without this check a cross-site page could drive either one; every other
 * route needs a bearer token, which a browser never attaches unprompted.
 *
 * <p>Tokens are obtained through the real {@code GET /auth/csrf} rather than Spring Security's
 * {@code csrf()} post-processor, so what is exercised is the contract a client has to implement.
 */
@SpringBootTest(properties = {"zarlania.throttle.endpoints.register.limit=1000"})
class CsrfEndToEndTest extends FlowTestBase {

  private static final String CSRF_COOKIE = "XSRF-TOKEN";

  @Test
  void refreshWithoutACsrfTokenIsRejectedEvenWithAValidRefreshCookie() throws Exception {
    registerAndVerify("mia@example.com", "mia");
    MvcResult login = auth.login("mia", PASSWORD).andExpect(status().isOk()).andReturn();
    String cookie = refreshCookieOf(login);

    mockMvc
        .perform(post("/auth/refresh").cookie(new Cookie(AuthEndpoints.REFRESH_COOKIE, cookie)))
        .andExpect(status().isForbidden());

    // And the refresh cookie is untouched by the rejected attempt, so the legitimate client can
    // still use it once it fetches a token — a rejection must not cost the user their session.
    auth.refresh(cookie).andExpect(status().isOk());
  }

  // The header alone is not enough: the cookie half of the double-submit pair has to match it. An
  // attacker's page can make the browser send the cookie but cannot read it to forge the header, so
  // a request carrying one without the other is exactly what a forgery looks like.
  @Test
  void refreshWithACsrfHeaderButNoMatchingCookieIsRejected() throws Exception {
    registerAndVerify("nils@example.com", "nils");
    MvcResult login = auth.login("nils", PASSWORD).andExpect(status().isOk()).andReturn();
    String cookie = refreshCookieOf(login);
    CsrfCredentials csrf = CsrfCredentials.fetch(mockMvc);

    mockMvc
        .perform(
            csrf.applyHeaderTo(
                    post("/auth/refresh").cookie(new Cookie(AuthEndpoints.REFRESH_COOKIE, cookie)))
                .cookie(new Cookie(CSRF_COOKIE, "not-the-token-that-was-issued")))
        .andExpect(status().isForbidden());
  }

  @Test
  void logoutWithoutACsrfTokenIsRejected() throws Exception {
    mockMvc.perform(post("/auth/logout")).andExpect(status().isForbidden());
  }

  // The endpoint has to hand back both halves, and name the header itself rather than leaving the
  // client to hardcode it: the browser client is served from another host, so it cannot read the
  // cookie, and a cold-started tab has no token in memory to reuse.
  @Test
  void theCsrfEndpointReturnsBothHalvesOfThePairAndNamesTheHeader() throws Exception {
    mockMvc
        .perform(get("/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
        .andExpect(jsonPath("$.token").isNotEmpty());
  }
}
