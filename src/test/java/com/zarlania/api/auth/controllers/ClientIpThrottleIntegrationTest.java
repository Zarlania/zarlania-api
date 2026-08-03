package com.zarlania.api.auth.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.PostgresTestContainer;
import java.time.Duration;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Covers the per-client-IP half of the throttle, and in particular <em>which</em> address the
 * bucket follows.
 *
 * <p>Requests here carry the full header set a live Render service receives — a three-entry {@code
 * X-Forwarded-For} plus {@code CF-Connecting-IP} — because the deployment sits behind two appending
 * hops (Cloudflare, then Render's load balancer) rather than one. Both ends of {@code
 * X-Forwarded-For} are wrong answers, in opposite directions: the leftmost entry is forged by the
 * caller, and the rightmost is Render's internal load balancer, one address shared by every request
 * from every user. Tests that synthesize a two-entry header cannot tell any of those apart.
 *
 * <p>{@code login-limit} stays at the production default so these exercise the real number. {@code
 * login-account-limit} is raised out of the way, since it would otherwise trip at the same request
 * count and leave every 429 ambiguous about which limit produced it; {@link
 * AccountThrottleIntegrationTest} is the mirror image. Every method uses its own client address so
 * no method's bucket state can bleed into another's, whatever order JUnit runs them in.
 */
@SpringBootTest(properties = {"zarlania.throttle.endpoints.login.account-limit=1000"})
@AutoConfigureMockMvc
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ClientIpThrottleIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String CLOUDFLARE_CLIENT_IP_HEADER = "CF-Connecting-IP";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final int LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING = 11;

  // The two infrastructure hops, from a probe of a live Render service. RENDER_LOAD_BALANCER is
  // also what getRemoteAddr() returns in production — the same private address for everybody.
  private static final String CLOUDFLARE_EDGE = "172.69.40.233";
  private static final String RENDER_LOAD_BALANCER = "10.24.118.242";

  // TEST-NET-2 (RFC 5737); one client address per test method.
  private static final String CLIENT_SHARED_BUCKET_TEST = "198.51.100.10";
  private static final String CLIENT_INDEPENDENCE_TEST_PRIMARY = "198.51.100.30";
  private static final String CLIENT_INDEPENDENCE_TEST_SECONDARY = "198.51.100.40";
  private static final String CLIENT_SPOOFING_TEST = "198.51.100.50";
  private static final String CLIENT_RETRY_AFTER_TEST = "198.51.100.60";
  private static final String UNPROXIED_REMOTE_ADDR = "203.0.113.7";

  // Mirrors zarlania.throttle.window, which these tests deliberately leave at its production value.
  private static final Duration THROTTLE_WINDOW = Duration.ofMinutes(1);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;

  @Test
  void requestsFromOneClientAddressShareAThrottleBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginAsDeployed(CLIENT_SHARED_BUCKET_TEST, "nobody-shared")
          .andExpect(status().isUnauthorized());
    }

    loginAsDeployed(CLIENT_SHARED_BUCKET_TEST, "nobody-shared")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // A 429 with no Retry-After leaves a client guessing, and a client that guesses short spends its
  // whole backoff being refused again. The value is whole seconds and never zero, so a client that
  // obeys it to the letter arrives after the window has genuinely refilled.
  @Test
  void aThrottledResponseTellsTheClientHowLongToWait() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginAsDeployed(CLIENT_RETRY_AFTER_TEST, "nobody-retry").andExpect(status().isUnauthorized());
    }

    loginAsDeployed(CLIENT_RETRY_AFTER_TEST, "nobody-retry")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
        .andExpect(
            result ->
                assertThat(
                        Integer.parseInt(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                    .isBetween(1, (int) THROTTLE_WINDOW.toSeconds()));
  }

  // Drives the primary client past its limit rather than only up to it: an assertion made while the
  // primary bucket still had capacity would pass even if the two clients shared one bucket — which
  // is exactly what keying on the Render load balancer's address would produce, since that entry is
  // identical in both requests.
  @Test
  void differentClientsBehindTheSameInfrastructureGetIndependentBuckets() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginAsDeployed(CLIENT_INDEPENDENCE_TEST_PRIMARY, "nobody-independent")
          .andExpect(status().isUnauthorized());
    }
    loginAsDeployed(CLIENT_INDEPENDENCE_TEST_PRIMARY, "nobody-independent")
        .andExpect(status().isTooManyRequests());

    // Same Cloudflare edge, same Render load balancer, different client: full allowance.
    loginAsDeployed(CLIENT_INDEPENDENCE_TEST_SECONDARY, "nobody-independent")
        .andExpect(status().isUnauthorized());
  }

  // The forgery half. Cloudflare overwrites CF-Connecting-IP with the address it saw, so a caller
  // varying what it prepends to X-Forwarded-For — the header Spring's framework strategy would key
  // on — stays in its own bucket and is still throttled on the eleventh request.
  @Test
  void aRotatingForgedForwardedForEntryStillLandsInTheRealClientsBucket() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginWithForgedForwardedFor(CLIENT_SPOOFING_TEST, attempt)
          .andExpect(status().isUnauthorized());
    }

    loginWithForgedForwardedFor(CLIENT_SPOOFING_TEST, LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // No Cloudflare header at all: the request never crossed the edge, so nothing in it can be
  // trusted and the bucket follows the TCP peer. Covers ClientIpResolver's fallback, which is the
  // local and unproxied case.
  @Test
  void withoutTheCloudflareHeaderTheBucketFollowsTheTcpPeer() throws Exception {
    for (int attempt = 1; attempt < LOGIN_ATTEMPTS_TO_TRIGGER_THROTTLING; attempt++) {
      loginFromUnproxiedPeer("nobody-direct").andExpect(status().isUnauthorized());
    }

    loginFromUnproxiedPeer("nobody-direct")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("auth.throttled"));
  }

  // The exact header set a live Render service receives.
  private ResultActions loginAsDeployed(String clientIp, String identifier) throws Exception {
    return performLogin(
        identifier,
        builder ->
            builder
                .header(FORWARDED_FOR_HEADER, deployedForwardedFor(clientIp))
                .header(CLOUDFLARE_CLIENT_IP_HEADER, clientIp));
  }

  // Same, except the caller prepends a different address of its own on every attempt.
  private ResultActions loginWithForgedForwardedFor(String clientIp, int attempt) throws Exception {
    return performLogin(
        "nobody-spoofed",
        builder ->
            builder
                .header(
                    FORWARDED_FOR_HEADER,
                    "192.0.2." + attempt + ", " + deployedForwardedFor(clientIp))
                .header(CLOUDFLARE_CLIENT_IP_HEADER, clientIp));
  }

  private ResultActions loginFromUnproxiedPeer(String identifier) throws Exception {
    return performLogin(
        identifier,
        builder ->
            builder.with(
                request -> {
                  request.setRemoteAddr(UNPROXIED_REMOTE_ADDR);
                  return request;
                }));
  }

  private static String deployedForwardedFor(String clientIp) {
    return clientIp + ", " + CLOUDFLARE_EDGE + ", " + RENDER_LOAD_BALANCER;
  }

  // Every identifier here is unregistered: these tests are about which bucket a request lands in,
  // and a 401 versus a 429 is all that has to be distinguishable. That the limit fires ahead of the
  // real credential path is asserted in AccountThrottleIntegrationTest against a live account.
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
