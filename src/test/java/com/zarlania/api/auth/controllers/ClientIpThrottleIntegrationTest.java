package com.zarlania.api.auth.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.PostgresTestContainer;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Covers the per-client-IP half of the throttle, and in particular <em>which</em> address the
 * bucket follows — the difference between a working limiter and one any unauthenticated caller can
 * step around by adding a header.
 *
 * <p>{@code login-limit} stays at the production default so these exercise the real number. {@code
 * login-account-limit} is raised out of the way, since it would otherwise trip at the same request
 * count and leave every 429 here ambiguous about which limit produced it; {@link
 * AccountThrottleIntegrationTest} is the mirror image. Every method uses its own client address so
 * no method's bucket state can bleed into another's, whatever order JUnit runs them in.
 */
@SpringBootTest(properties = {"zarlania.throttle.login-account-limit=1000"})
@AutoConfigureMockMvc
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ClientIpThrottleIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final int LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING = 11;

  private static final String DIRECT_REMOTE_ADDR = "203.0.113.7";
  // TEST-NET-2 (RFC 5737); one per test method.
  private static final String FORWARDED_ADDR_SHARED_BUCKET_TEST = "198.51.100.10";
  private static final String FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY = "198.51.100.30";
  private static final String FORWARDED_ADDR_INDEPENDENCE_TEST_SECONDARY = "198.51.100.40";
  private static final String FORWARDED_ADDR_SPOOFING_TEST = "198.51.100.50";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;

  // No X-Forwarded-For at all, so this covers ClientIpResolver's fallback to the direct peer
  // address — the local and unproxied case.
  @Test
  void theEleventhRapidLoginFromOneUnproxiedAddressIsThrottled() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestFrom(DIRECT_REMOTE_ADDR, "nobody-direct").andExpect(status().isUnauthorized());
    }

    loginRequestFrom(DIRECT_REMOTE_ADDR, "nobody-direct")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // ClientIpResolver reads X-Forwarded-For so a caller resolves to itself rather than to Render's
  // proxy, which would otherwise put every user of the service in one bucket.
  @Test
  void requestsSharingAForwardedForAddressShareAThrottleBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(FORWARDED_ADDR_SHARED_BUCKET_TEST, "nobody-shared")
          .andExpect(status().isUnauthorized());
    }

    loginRequestForwardedFrom(FORWARDED_ADDR_SHARED_BUCKET_TEST, "nobody-shared")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // Drives the primary address past its limit rather than only up to it: an assertion made while
  // the primary bucket still had capacity would pass even if the two addresses shared one bucket.
  @Test
  void requestsWithDifferentForwardedForAddressesGetIndependentThrottleBuckets() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY, "nobody-independent")
          .andExpect(status().isUnauthorized());
    }
    loginRequestForwardedFrom(FORWARDED_ADDR_INDEPENDENCE_TEST_PRIMARY, "nobody-independent")
        .andExpect(status().isTooManyRequests());

    // Primary is exhausted; a different forwarded address must still have its full allowance.
    loginRequestForwardedFrom(FORWARDED_ADDR_INDEPENDENCE_TEST_SECONDARY, "nobody-independent")
        .andExpect(status().isUnauthorized());
  }

  // The bypass this whole resolver exists to close. A proxy appends to X-Forwarded-For rather than
  // replacing it, so everything left of the last entry is attacker-supplied: every request below
  // presents a *different* forged leftmost entry and the same proxy-appended rightmost one. Keying
  // on the leftmost entry — which is what server.forward-headers-strategy: framework does — would
  // hand each request a fresh bucket, and nothing would ever be throttled.
  @Test
  void aRotatingForgedLeftmostForwardedForEntryStillLandsInTheRealCallersBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginRequestForwardedFrom(forgedHeaderFor(attempt), "nobody-spoofed")
          .andExpect(status().isUnauthorized());
    }

    loginRequestForwardedFrom(
            forgedHeaderFor(LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING), "nobody-spoofed")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // TEST-NET-1 (RFC 5737) for the forged half, so the two entries are visibly different networks.
  private static String forgedHeaderFor(int attempt) {
    return "192.0.2." + attempt + ", " + FORWARDED_ADDR_SPOOFING_TEST;
  }

  private ResultActions loginRequestFrom(String remoteAddr, String identifier) throws Exception {
    return performLogin(
        identifier,
        builder ->
            builder.with(
                request -> {
                  request.setRemoteAddr(remoteAddr);
                  return request;
                }));
  }

  // Takes the whole header value, not one address, so a test can present the multi-entry form a
  // real proxy produces.
  private ResultActions loginRequestForwardedFrom(String forwardedFor, String identifier)
      throws Exception {
    return performLogin(identifier, builder -> builder.header(FORWARDED_FOR_HEADER, forwardedFor));
  }

  // Every identifier here is unregistered: these tests are about which bucket a request lands in,
  // and a 401 versus a 429 is all that has to be distinguishable.
  private ResultActions performLogin(
      String identifier, UnaryOperator<MockHttpServletRequestBuilder> customize) throws Exception {
    return mockMvc.perform(
        customize.apply(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"identifier":"%s","password":"%s"}
                    """
                        .formatted(identifier, PASSWORD))));
  }
}
