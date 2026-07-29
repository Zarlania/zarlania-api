package com.zarlania.api.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Every case here is built from the header shape a live Render service actually receives, rather
 * than from a two-entry approximation. The approximation is what let the original bypass survive: a
 * test that synthesizes {@code client, proxy} and then declares the last entry to be the proxy has
 * assumed its own conclusion, and passes just as happily when the assumption is wrong.
 *
 * <p>Real chain: client ──▶ Cloudflare edge ──▶ Render load balancer ──▶ app.
 */
class ClientIpResolverTest {

  private static final String CLOUDFLARE_CLIENT_IP = "CF-Connecting-IP";
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private static final String REAL_CLIENT = "208.54.226.138";
  private static final String CLOUDFLARE_EDGE = "172.69.40.233";
  private static final String RENDER_LOAD_BALANCER = "10.24.118.242";
  // What getRemoteAddr() returns in production: the Render load balancer, identical for everyone.
  private static final String TCP_PEER = RENDER_LOAD_BALANCER;
  private static final String FORGED = "1.2.3.4";

  private static final String DEPLOYED_FORWARDED_FOR =
      REAL_CLIENT + ", " + CLOUDFLARE_EDGE + ", " + RENDER_LOAD_BALANCER;

  @Test
  void resolvesTheRealClientFromTheDeployedThreeEntryHeaderShape() {
    assertThat(ClientIpResolver.resolve(deployedRequest(REAL_CLIENT))).isEqualTo(REAL_CLIENT);
  }

  // The two entries a hop-counting implementation would land on. Neither is the client: the
  // leftmost is forgeable, and the rightmost is one address shared by the entire service.
  @Test
  void neverResolvesToTheCloudflareEdgeOrTheRenderLoadBalancer() {
    String resolved = ClientIpResolver.resolve(deployedRequest(REAL_CLIENT));

    assertThat(resolved).isNotEqualTo(CLOUDFLARE_EDGE).isNotEqualTo(RENDER_LOAD_BALANCER);
  }

  // A client prepending entries to X-Forwarded-For changes nothing, because that header is not read
  // at all — Cloudflare overwrites CF-Connecting-IP with the address it saw.
  @Test
  void aForgedForwardedForEntryCannotDisplaceTheCloudflareHeader() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(FORWARDED_FOR, FORGED + ", " + DEPLOYED_FORWARDED_FOR);
    request.addHeader(CLOUDFLARE_CLIENT_IP, REAL_CLIENT);

    assertThat(ClientIpResolver.resolve(request)).isEqualTo(REAL_CLIENT);
  }

  // The fallback must never be client-controllable. With no Cloudflare header the request did not
  // come through the edge, so X-Forwarded-For is entirely the caller's word and is ignored in
  // favour of the TCP peer — a shared bucket, which is degraded but not forgeable.
  @Test
  void withoutTheCloudflareHeaderAForgedForwardedForIsIgnoredInFavourOfTheTcpPeer() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(FORWARDED_FOR, FORGED);

    assertThat(ClientIpResolver.resolve(request)).isEqualTo(TCP_PEER).isNotEqualTo(FORGED);
  }

  @Test
  void withNoHeadersAtAllTheTcpPeerIsUsed() {
    assertThat(ClientIpResolver.resolve(requestFromTcpPeer())).isEqualTo(TCP_PEER);
  }

  // Repeated header lines rather than one comma-joined value. getHeader() would return the first
  // line — the client's — so this is the case that decides whether the header stays unforgeable.
  // The edge's line is written last, so the last line wins.
  @Test
  void aClientSuppliedCloudflareHeaderLineLosesToTheEdgesLine() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, FORGED);
    request.addHeader(CLOUDFLARE_CLIENT_IP, REAL_CLIENT);

    assertThat(ClientIpResolver.resolve(request)).isEqualTo(REAL_CLIENT).isNotEqualTo(FORGED);
  }

  @Test
  void aBlankCloudflareHeaderFallsBackToTheTcpPeer() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, "   ");

    assertThat(ClientIpResolver.resolve(request)).isEqualTo(TCP_PEER);
  }

  @Test
  void surroundingWhitespaceIsTrimmedSoOneClientKeepsOneBucket() {
    assertThat(ClientIpResolver.resolve(deployedRequest("  " + REAL_CLIENT + "  ")))
        .isEqualTo(REAL_CLIENT);
  }

  // Only reachable off the Cloudflare path, where the header is not the edge's; bounded so one
  // caller cannot mint unbounded distinct keys in the limiter's map.
  @Test
  void anAbsurdlyLongValueIsTruncatedToTheLongestPossibleAddress() {
    assertThat(ClientIpResolver.resolve(deployedRequest("x".repeat(500)))).hasSize(45);
  }

  private static MockHttpServletRequest requestFromTcpPeer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(TCP_PEER);
    return request;
  }

  private static MockHttpServletRequest deployedRequest(String cloudflareClientIp) {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(FORWARDED_FOR, DEPLOYED_FORWARDED_FOR);
    request.addHeader(CLOUDFLARE_CLIENT_IP, cloudflareClientIp);
    return request;
  }
}
