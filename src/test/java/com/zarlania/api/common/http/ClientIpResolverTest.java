package com.zarlania.api.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The whole point of this class is <em>which</em> {@code X-Forwarded-For} entry is trusted, so
 * every test below is about that choice rather than about parsing.
 */
class ClientIpResolverTest {

  private static final String FORWARDED_FOR = "X-Forwarded-For";
  private static final String PROXY_APPENDED = "198.51.100.7";
  private static final String CLIENT_FORGED = "1.2.3.4";
  private static final String DIRECT_PEER = "203.0.113.9";

  @Test
  void withNoForwardedHeaderTheDirectPeerAddressIsUsed() {
    assertThat(ClientIpResolver.resolve(request(null))).isEqualTo(DIRECT_PEER);
  }

  @Test
  void aBlankForwardedHeaderFallsBackToTheDirectPeerAddress() {
    assertThat(ClientIpResolver.resolve(request("   "))).isEqualTo(DIRECT_PEER);
  }

  @Test
  void aSingleForwardedEntryIsTheProxysOwnAndIsUsed() {
    assertThat(ClientIpResolver.resolve(request(PROXY_APPENDED))).isEqualTo(PROXY_APPENDED);
  }

  // The bypass this class exists to close: a proxy appends rather than replaces, so the leftmost
  // entry is whatever the caller typed. Keying on it would hand an attacker a fresh throttle
  // bucket for every request simply by varying the header.
  @Test
  void aForgedLeftmostEntryIsIgnoredInFavourOfTheProxyAppendedRightmostOne() {
    assertThat(ClientIpResolver.resolve(request(CLIENT_FORGED + ", " + PROXY_APPENDED)))
        .isEqualTo(PROXY_APPENDED);
  }

  @Test
  void severalForgedEntriesStillResolveToTheRightmostOne() {
    String forged = CLIENT_FORGED + ", 5.6.7.8, 9.10.11.12, " + PROXY_APPENDED;

    assertThat(ClientIpResolver.resolve(request(forged))).isEqualTo(PROXY_APPENDED);
  }

  @Test
  void aTrailingSeparatorWithNoAddressAfterItFallsBackToTheDirectPeerAddress() {
    assertThat(ClientIpResolver.resolve(request(CLIENT_FORGED + ","))).isEqualTo(DIRECT_PEER);
  }

  // An unproxied caller controls the whole header, so the key it produces is bounded here rather
  // than letting one client mint arbitrarily long entries in the limiter's map.
  @Test
  void anAbsurdlyLongEntryIsTruncatedToTheLongestPossibleAddress() {
    assertThat(ClientIpResolver.resolve(request("x".repeat(500)))).hasSize(45);
  }

  private static MockHttpServletRequest request(String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(DIRECT_PEER);
    if (forwardedFor != null) {
      request.addHeader(FORWARDED_FOR, forwardedFor);
    }
    return request;
  }
}
