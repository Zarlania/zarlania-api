package com.zarlania.api.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A CSRF token pair fetched from {@code GET /auth/csrf}, ready to attach to the two requests that
 * require one: {@code POST /auth/refresh} and {@code POST /auth/logout}.
 *
 * <p>Tests go through the real endpoint rather than Spring Security's {@code csrf()} request
 * post-processor on purpose. The post-processor reaches inside the filter chain and would pass
 * whatever the server is configured to accept; this walks the same two steps the browser client has
 * to implement — fetch the token, then send it back in the header the server named, alongside the
 * cookie the server set — so the contract itself is what the tests exercise.
 */
public final class CsrfCredentials {

  // CookieCsrfTokenRepository's default cookie name. The header name is not hardcoded here because
  // the endpoint reports it, which is the point of returning it at all.
  private static final String CSRF_COOKIE = "XSRF-TOKEN";

  private final String headerName;
  private final String token;
  private final Cookie cookie;

  private CsrfCredentials(String headerName, String token, Cookie cookie) {
    this.headerName = headerName;
    this.token = token;
    this.cookie = cookie;
  }

  /**
   * Fetches a matching token-and-cookie pair the way a real client does, over the CSRF endpoint.
   */
  public static CsrfCredentials fetch(MockMvc mockMvc) throws Exception {
    MvcResult result = mockMvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
    String body = result.getResponse().getContentAsString();
    return new CsrfCredentials(
        JsonPath.read(body, "$.headerName"),
        JsonPath.read(body, "$.token"),
        result.getResponse().getCookie(CSRF_COOKIE));
  }

  /** Attaches both halves of the pair, which is what a request to a guarded route needs. */
  public MockHttpServletRequestBuilder applyTo(MockHttpServletRequestBuilder request) {
    return applyHeaderTo(request).cookie(cookie);
  }

  /**
   * The token value the endpoint handed back, for a test that has to place it somewhere {@link
   * #applyTo} would not — a form parameter, say.
   */
  public String token() {
    return token;
  }

  /**
   * Attaches only the cookie half of the pair. The mirror of {@link #applyHeaderTo}, for asserting
   * that a caller holding a genuine cookie still gets nowhere without the header.
   */
  public MockHttpServletRequestBuilder applyCookieTo(MockHttpServletRequestBuilder request) {
    return request.cookie(cookie);
  }

  /**
   * Attaches only the header half of the pair, leaving the caller to decide what cookie (if any)
   * travels with it. For asserting that the server compares the two rather than merely checking
   * that a header is present — {@code applyTo} cannot express that, because MockMvc's {@code
   * cookie()} appends rather than replaces, so the genuine cookie would still be found alongside
   * whatever the test added.
   */
  public MockHttpServletRequestBuilder applyHeaderTo(MockHttpServletRequestBuilder request) {
    return request.header(headerName, token);
  }
}
