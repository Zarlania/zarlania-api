package com.zarlania.api.auth.services;

/**
 * Names of the claims this service puts on its access tokens beyond the registered ones (which come
 * from {@code JwtClaimNames}).
 *
 * <p>One home for both ends of a single wire contract: {@code JwtService} writes these and {@code
 * SecurityConfig}'s authentication converter reads them, so a private copy in each is two places
 * for one string to drift — and a drift that would only show up as tokens this service issues
 * failing to authenticate against itself.
 *
 * <p>Constants rather than an enum, unlike {@link TokenKind} next door, because these are claim
 * <em>names</em> and not a value domain. Nothing branches on which claim name it holds, and both
 * the write side ({@code JWTClaimsSet.Builder#claim}) and the read side ({@code
 * Jwt#getClaimAsString}) take a bare {@code String} — so an enum would buy no compile-time check
 * and cost an unwrapping call at every use site. {@link TokenKind} is an enum precisely because it
 * is the opposite case: a closed set of values that code decides on.
 */
public final class TokenClaims {

  public static final String ORGANIZATION = "org";
  public static final String KIND = "kind";

  private TokenClaims() {}
}
