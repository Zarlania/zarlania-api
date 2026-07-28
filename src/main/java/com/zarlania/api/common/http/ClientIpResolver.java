package com.zarlania.api.common.http;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the address a request should be attributed to — the identity {@code AuthController}'s
 * throttle buckets are keyed on.
 *
 * <p>Render terminates TLS and proxies every request to this instance, so {@link
 * HttpServletRequest#getRemoteAddr()} on its own always resolves to Render's proxy, collapsing
 * every caller into one shared bucket per endpoint. The caller's own address is in {@code
 * X-Forwarded-For}, but <strong>which entry of it is read decides whether the throttle works at
 * all</strong>:
 *
 * <ul>
 *   <li>A proxy <em>appends</em> the address it received the request from; it does not replace the
 *       header. So on {@code X-Forwarded-For: a, b, c}, {@code c} was written by the last (nearest,
 *       trusted) proxy, and everything to its left is whatever the client sent — fully attacker
 *       controlled.
 *   <li>The <strong>leftmost</strong> entry is therefore forgeable by any unauthenticated caller.
 *       It is also what Spring's {@code ForwardedHeaderFilter} uses ({@code
 *       ForwardedHeaderUtils.parseForwardedFor} takes index 0, and prefers a client-supplied {@code
 *       Forwarded:} header outright — Render sets none, so an attacker's is used verbatim). Keying
 *       on it lets a caller rotate the header and get a fresh bucket per request: unlimited login
 *       brute force, unlimited registration email-bombing, unlimited resend.
 *   <li>The <strong>rightmost</strong> entry is the one this service can trust, because Render
 *       wrote it. That is what {@link #resolve} returns.
 * </ul>
 *
 * <p>This is correct for exactly one trusted proxy hop, which is the deployment ({@code
 * render.yaml}: one web service behind Render's load balancer). Add a second trusted proxy in front
 * and the rightmost entry becomes that inner proxy's address rather than the client's — at which
 * point this has to walk right to left discarding known-trusted hops, which is what Tomcat's {@code
 * RemoteIpValve} does given {@code server.tomcat.remoteip.internal-proxies}. That route was not
 * taken here because Render publishes no stable egress ranges to enumerate, and an internal-proxies
 * pattern that silently stops matching fails back to the forgeable leftmost value.
 *
 * <p>Consequently {@code server.forward-headers-strategy} must stay {@code none}: {@code framework}
 * both applies the wrong (leftmost) semantic and strips these headers before a handler can see
 * them.
 */
public final class ClientIpResolver {

  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final String ENTRY_SEPARATOR = ",";

  // Longest textual IPv6 address, IPv4-mapped form included, is 45 characters. The rightmost entry
  // comes from the proxy and so is well-formed in production; truncating is a cheap guard on the
  // one path where it might not be (a direct, unproxied request) against a caller minting
  // unbounded distinct keys in the limiter's map.
  private static final int MAX_ADDRESS_LENGTH = 45;

  private ClientIpResolver() {}

  // SERVLET_HEADER: the detector's point — that a client can set this header — is the premise this
  // method is built on, not a defect in it. Everything the client can write is discarded: only the
  // rightmost entry is read, and that one is written by Render's proxy after the request leaves the
  // client's control. The value is used solely as a throttle bucket key, never as an authorization
  // decision, an audit identity, or anything that reaches a query or a response.
  @SuppressFBWarnings(
      value = "SERVLET_HEADER",
      justification =
          "Client-controllable entries are deliberately discarded; only the rightmost entry,"
              + " written by the trusted proxy, is read, and it keys a rate-limit bucket rather"
              + " than any authorization or identity decision.")
  public static String resolve(HttpServletRequest request) {
    String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
    if (forwardedFor == null || forwardedFor.isBlank()) {
      return request.getRemoteAddr();
    }
    String nearestProxyEntry = rightmostEntry(forwardedFor);
    return nearestProxyEntry.isEmpty() ? request.getRemoteAddr() : truncate(nearestProxyEntry);
  }

  private static String rightmostEntry(String forwardedFor) {
    return forwardedFor.substring(forwardedFor.lastIndexOf(ENTRY_SEPARATOR) + 1).trim();
  }

  private static String truncate(String address) {
    return address.length() <= MAX_ADDRESS_LENGTH
        ? address
        : address.substring(0, MAX_ADDRESS_LENGTH);
  }
}
