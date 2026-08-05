package com.zarlania.api.http;

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
class CloudflareClientIpResolverTest {

  private final CloudflareClientIpResolver resolver = new CloudflareClientIpResolver();

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
    assertThat(resolver.resolve(deployedRequest(REAL_CLIENT))).isEqualTo(REAL_CLIENT);
  }

  // The two entries a hop-counting implementation would land on. Neither is the client: the
  // leftmost is forgeable, and the rightmost is one address shared by the entire service.
  @Test
  void neverResolvesToTheCloudflareEdgeOrTheRenderLoadBalancer() {
    String resolved = resolver.resolve(deployedRequest(REAL_CLIENT));

    assertThat(resolved).isNotEqualTo(CLOUDFLARE_EDGE).isNotEqualTo(RENDER_LOAD_BALANCER);
  }

  // A client prepending entries to X-Forwarded-For changes nothing, because that header is not read
  // at all — Cloudflare overwrites CF-Connecting-IP with the address it saw.
  @Test
  void aForgedForwardedForEntryCannotDisplaceTheCloudflareHeader() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(FORWARDED_FOR, FORGED + ", " + DEPLOYED_FORWARDED_FOR);
    request.addHeader(CLOUDFLARE_CLIENT_IP, REAL_CLIENT);

    assertThat(resolver.resolve(request)).isEqualTo(REAL_CLIENT);
  }

  // The fallback must never be client-controllable. With no Cloudflare header the request did not
  // come through the edge, so X-Forwarded-For is entirely the caller's word and is ignored in
  // favour of the TCP peer — a shared bucket, which is degraded but not forgeable.
  @Test
  void withoutTheCloudflareHeaderAForgedForwardedForIsIgnoredInFavourOfTheTcpPeer() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(FORWARDED_FOR, FORGED);

    assertThat(resolver.resolve(request)).isEqualTo(TCP_PEER).isNotEqualTo(FORGED);
  }

  @Test
  void withNoHeadersAtAllTheTcpPeerIsUsed() {
    assertThat(resolver.resolve(requestFromTcpPeer())).isEqualTo(TCP_PEER);
  }

  // Repeated header lines rather than one comma-joined value. getHeader() would return the first
  // line — the client's — so this is the case that decides whether the header stays unforgeable.
  // The edge's line is written last, so the last line wins.
  @Test
  void aClientSuppliedCloudflareHeaderLineLosesToTheEdgesLine() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, FORGED);
    request.addHeader(CLOUDFLARE_CLIENT_IP, REAL_CLIENT);

    assertThat(resolver.resolve(request)).isEqualTo(REAL_CLIENT).isNotEqualTo(FORGED);
  }

  // The sibling of the case above, and the one that stayed forgeable after it was fixed: RFC 9110
  // §5.3 makes two header lines and one comma-joined line interchangeable, and any recipient in the
  // chain may fold one into the other. So the client's value can arrive *inside* the edge's line.
  // Neither embedded address may be trusted — the whole value is not one address, so it is not an
  // answer, and the peer is used instead.
  @Test
  void aCommaFoldedCloudflareHeaderFallsBackToTheTcpPeerAndTrustsNeitherValue() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, FORGED + ", " + REAL_CLIENT);

    assertThat(resolver.resolve(request))
        .isEqualTo(TCP_PEER)
        .isNotEqualTo(FORGED)
        .isNotEqualTo(REAL_CLIENT);
  }

  // Everything the guard closes in the same move as the folded case. Each of these would otherwise
  // become a bucket key that varies with whatever the caller sent.
  @Test
  void valuesThatAreNotOneBareIpLiteralFallBackToTheTcpPeer() {
    assertThat(resolveWithCloudflareHeader(REAL_CLIENT + ":8080")).isEqualTo(TCP_PEER);
    assertThat(resolveWithCloudflareHeader("unknown")).isEqualTo(TCP_PEER);
    assertThat(resolveWithCloudflareHeader("fe80::1%eth0")).isEqualTo(TCP_PEER);
    assertThat(resolveWithCloudflareHeader("x".repeat(500))).isEqualTo(TCP_PEER);
    assertThat(resolveWithCloudflareHeader("not an address")).isEqualTo(TCP_PEER);
  }

  // The scope id case above only proves the guard rejects an interface name that plausibly does
  // not exist on the host running the test — "eth0" is real on a typical Linux box and absent on
  // macOS, which is exactly what let this guard's absence pass unnoticed on one platform and fail
  // on the other. Repeating it against "lo", the loopback interface that exists on every platform
  // this test runs on, proves the guard rejects the scope id itself rather than merely failing to
  // recognise an interface name — it would still reject even if the address happened to resolve.
  @Test
  void aScopeIdIsRejectedEvenWhenTheInterfaceNameExistsLocally() {
    assertThat(resolveWithCloudflareHeader("fe80::1%lo")).isEqualTo(TCP_PEER);
  }

  // Two spellings of one address must not become two buckets — the same reasoning that normalizes
  // the per-account keys in AuthController.
  @Test
  void alternateSpellingsOfOneAddressResolveToOneBucketKey() {
    assertThat(resolveWithCloudflareHeader("[::1]")).isEqualTo(resolveWithCloudflareHeader("::1"));
    assertThat(resolveWithCloudflareHeader("::ffff:10.0.0.1"))
        .isEqualTo(resolveWithCloudflareHeader("10.0.0.1"));
  }

  @Test
  void aBlankCloudflareHeaderFallsBackToTheTcpPeer() {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, "   ");

    assertThat(resolver.resolve(request)).isEqualTo(TCP_PEER);
  }

  @Test
  void surroundingWhitespaceIsTrimmedSoOneClientKeepsOneBucket() {
    assertThat(resolver.resolve(deployedRequest("  " + REAL_CLIENT + "  "))).isEqualTo(REAL_CLIENT);
  }

  private String resolveWithCloudflareHeader(String value) {
    MockHttpServletRequest request = requestFromTcpPeer();
    request.addHeader(CLOUDFLARE_CLIENT_IP, value);
    return resolver.resolve(request);
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
