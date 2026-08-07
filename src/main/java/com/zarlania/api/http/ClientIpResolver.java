package com.zarlania.api.http;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Port for deciding which address a request should be attributed to — the identity {@code
 * ThrottleAspect}'s buckets are keyed on.
 *
 * <p>An interface because the answer is a fact about the deployment, not about this application.
 * {@link CloudflareClientIpResolver} is correct only while every request reaches the service
 * through Cloudflare's edge; behind a different CDN, a different load balancer, or nothing at all,
 * the header to trust changes and the current one becomes forgeable. Making that a choice of
 * implementation means moving providers is a new class and a bean, not an edit to logic that other
 * code depends on being right.
 *
 * <p>Any implementation must satisfy one contract, and it is a security property rather than a
 * convenience: <strong>the value returned must never be one the client could have chosen.</strong>
 * A resolver that reads a header an unprivileged caller can set hands every throttle bucket a
 * bypass — rotate the header, get a fresh bucket, and the limit is gone. Where the trusted source
 * is absent, degrade to something unforgeable and shared (the TCP peer) rather than to anything the
 * request carried.
 */
public interface ClientIpResolver {

  /**
   * Resolves the address this request should be attributed to.
   *
   * <p>The result keys a rate-limit bucket only — never an authorization decision, an audit
   * identity, a query, or a response. That narrow use is what makes trusting a proxy header
   * defensible at all.
   *
   * @return a canonical IP literal, at most 45 characters, so no caller can mint unbounded distinct
   *     bucket keys. Implementations must never return null.
   */
  String resolve(HttpServletRequest request);
}
