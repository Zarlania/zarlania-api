package com.zarlania.api.common.http;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.Optional;

/**
 * Resolves the address a request should be attributed to — the identity {@code AuthController}'s
 * throttle buckets are keyed on.
 *
 * <p>The deployed request chain has <strong>two</strong> appending hops, not one: Render fronts
 * every service with Cloudflare as well as its own load balancer.
 *
 * <pre>
 *   client ──▶ Cloudflare edge ──▶ Render load balancer ──▶ this app
 *
 *   X-Forwarded-For: 208.54.226.138, 172.69.40.233, 10.24.118.242
 *                    ^ real client    ^ Cloudflare    ^ Render LB
 *   CF-Connecting-IP: 208.54.226.138
 * </pre>
 *
 * <p>Neither end of {@code X-Forwarded-For} is usable, which is why this class reads a different
 * header entirely:
 *
 * <ul>
 *   <li>The <strong>leftmost</strong> entry is whatever the client sent — a proxy appends rather
 *       than replaces — so keying on it lets any caller rotate the header for a fresh bucket per
 *       request, which is unlimited login brute force, registration email-bombing and resend. It is
 *       also what {@code server.forward-headers-strategy: framework} would rewrite {@code
 *       getRemoteAddr()} from — which is why that setting must stay {@code none}: not because it
 *       would hide any header from this class ({@code ForwardedHeaderFilter} removes only {@code
 *       Forwarded} and the {@code X-Forwarded-*} family, so {@code CF-Connecting-IP} survives it),
 *       but because it would make the fallback below forgeable.
 *   <li>The <strong>rightmost</strong> entry is the Render load balancer's private {@code 10.x}
 *       address, byte-identical for every request from every user. Keying on it collapses all four
 *       auth endpoints into one global bucket — ten requests from anybody would exhaust login for
 *       the whole service. Safe from forgery, useless as an identity.
 *   <li>The real client is third from the right, but only because there happen to be exactly two
 *       trusted hops today. A hop count is a number that changes silently when the platform
 *       changes, and this one has changed once already.
 * </ul>
 *
 * <p><strong>{@code CF-Connecting-IP} is read instead, because Cloudflare <em>replaces</em> it
 * rather than appending to it.</strong> That is the property that matters: a value supplied by the
 * client cannot survive the edge, so there is nothing to strip, count hops through, or trust
 * conditionally. It is single-valued by contract, so no entry has to be picked out of a list.
 *
 * <p>When the header is absent the fallback is {@link HttpServletRequest#getRemoteAddr()} — the TCP
 * peer, which no client can set. In production that is the Render load balancer, so a request
 * arriving without the Cloudflare header lands in one shared bucket: degraded, but never forgeable.
 * Every fallback here is to that same address for exactly that reason — falling back to any
 * client-supplied value would hand the bypass straight back. Locally and under test the peer is the
 * caller itself, which is the right answer there. A header value that is not one bare IP literal —
 * a comma-folded pair, a port suffix, junk — falls back the same way instead of being used as-is;
 * see {@link #canonicalAddress}.
 *
 * <p><strong>This header being unforgeable is a platform assumption, not a protocol
 * guarantee.</strong> Nothing in HTTP stops a client sending {@code CF-Connecting-IP}; what makes
 * it trustworthy is that Cloudflare overwrites it, and that holds only while every request reaches
 * this app through the edge — which Render gives this project no way to enforce. It belongs in the
 * same category as the hop count above: true of the platform today, and able to change without any
 * code here changing.
 *
 * <p>The alternative was {@code server.forward-headers-strategy: native} with {@code
 * server.tomcat.remoteip.internal-proxies} covering Tomcat's private-range defaults plus
 * Cloudflare's published ranges. {@code RemoteIpValve} walks right to left and stops at the first
 * entry that does not match, so it is correct and never lands on a forgeable value either. It was
 * not chosen because it means tracking Cloudflare's published ranges as they change, and a stale
 * list fails quietly: the walk stops at an infrastructure address and the shared bucket comes back
 * with nothing raised. A header Cloudflare guarantees to overwrite has no list to go stale.
 */
public final class ClientIpResolver {

  // Set by Cloudflare on every request it proxies, overwriting any value the client sent.
  private static final String CLOUDFLARE_CLIENT_IP_HEADER = "CF-Connecting-IP";

  private ClientIpResolver() {}

  // Reading a request header is only sound here because of what this particular header is:
  // CF-Connecting-IP is the one in the chain a client cannot dictate, since Cloudflare replaces it
  // at the edge on every request it proxies. A request that never crossed the edge carries no such
  // header and falls back to the TCP peer rather than to anything the caller wrote. The result keys
  // a rate-limit bucket only — never an authorization decision, an audit identity, a query, or a
  // response. (FindSecBugs's SERVLET_HEADER fires on getHeader, not getHeaders, so no suppression
  // is needed; if that ever changes, this paragraph is the justification.)
  public static String resolve(HttpServletRequest request) {
    return lastHeaderValue(request, CLOUDFLARE_CLIENT_IP_HEADER)
        .flatMap(ClientIpResolver::canonicalAddress)
        .orElseGet(request::getRemoteAddr);
  }

  // getHeaders, not getHeader: getHeader returns only the *first* line of a repeated header. If a
  // client's own CF-Connecting-IP line and the edge's ever arrived as two lines rather than the
  // edge overwriting the one, getHeader would read the client's and the header would be forgeable
  // again. The last line is the one written latest in the chain, so that is the one taken.
  private static Optional<String> lastHeaderValue(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null) {
      return Optional.empty();
    }
    String lastValue = null;
    while (values.hasMoreElements()) {
      lastValue = values.nextElement();
    }
    return Optional.ofNullable(lastValue).map(String::trim);
  }

  // The value has to parse as one bare IP literal or it is not used at all, because taking the last
  // header line is only half the defence. RFC 9110 §5.3 makes `A: x` and `A: y` interchangeable
  // with `A: x, y`, and any recipient in the chain may fold one form into the other — so a client
  // line surviving alongside the edge's can arrive comma-folded into a single line, and
  // "1.2.3.4, 208.54.226.138" would otherwise become the bucket key verbatim and vary with whatever
  // the caller sent. Requiring a bare literal rejects that in the same move as a port suffix, an
  // `unknown` token, a scope id, and any other junk: every shape this does not recognise becomes
  // the shared TCP-peer bucket, which is the invariant this class claims throughout.
  //
  // InetAddress.ofLiteral, not getByName: ofLiteral parses textual forms only and never performs a
  // DNS lookup, so an unrecognised value cannot turn into a blocking network call from the request
  // thread. getHostAddress then canonicalises the parse — "[::1]" and "::1", or "::ffff:10.0.0.1"
  // and "10.0.0.1", are one address and must not become two buckets, for the same reason the
  // per-account keys in AuthController are normalised. It also bounds the key: a parsed literal is
  // at most 45 characters, so no caller can mint unbounded distinct keys in the limiter's map.
  private static Optional<String> canonicalAddress(String value) {
    try {
      return Optional.of(InetAddress.ofLiteral(value).getHostAddress());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
