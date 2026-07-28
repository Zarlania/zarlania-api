package com.zarlania.api.auth.services;

/**
 * Names of the claims this service puts on its access tokens beyond the registered ones (which come
 * from {@code JwtClaimNames}).
 *
 * <p>One home for both ends of a single wire contract: {@code JwtService} writes these and {@code
 * SecurityConfig}'s authentication converter reads them, so a private copy in each is two places
 * for one string to drift — and a drift that would only show up as tokens this service issues
 * failing to authenticate against itself.
 */
public final class TokenClaims {

  public static final String ORGANIZATION = "org";
  public static final String KIND = "kind";

  private TokenClaims() {}
}
