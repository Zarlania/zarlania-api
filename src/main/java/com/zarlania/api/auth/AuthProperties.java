package com.zarlania.api.auth;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code zarlania.auth} configuration block; see {@code application.yml}. */
@ConfigurationProperties(prefix = "zarlania.auth")
public record AuthProperties(
    String issuer,
    Duration accessTokenTtl,
    Duration refreshFamilyLifetime,
    Duration unverifiedAccountMaxAge,
    boolean cookieSecure,
    String jwtPrivateKeyPem,
    String jwtRetiredPublicKeysPem,
    String appBaseUrl) {

  /**
   * Rejects configuration this domain cannot run on, at startup rather than at the first request.
   *
   * <p>None of these fail where they are set. The issuer goes into every access token's {@code iss}
   * and is compared again on the way back in, so a blank one is minted and then rejected by this
   * same service. A non-positive lifetime is not an unlimited one: an access token expires at the
   * instant it is minted, a refresh family is dead before its first rotation, and an unverified
   * account max age purges every registration the moment it is made. And {@code appBaseUrl} is the
   * origin of every verification link emailed out, so a blank one sends the recipient to a relative
   * path — the account can then never be verified, and an unverified account cannot log in. Zero is
   * what an unset or misspelled duration key binds to.
   *
   * <p>Neither key property is checked here. A blank private key is how every non-production
   * deployment runs, {@code JwtKeys} is the only thing that knows the profile, and it also owns
   * whether a retired-key block parses — so both stay its decision, exactly as a blank provider key
   * stays {@code EmailSenderFactory}'s.
   *
   * <p>{@code cookieSecure} needs nothing: a boolean has no unusable value, and both of its values
   * are meaningful — false is what lets a local deployment over plain HTTP keep its session.
   *
   * <p>Every message names the property as it is written in configuration, not the record component
   * — whoever reads one is looking at {@code application.yml} or an environment variable.
   *
   * @throws NullPointerException if any of the three durations is not configured
   * @throws IllegalArgumentException if {@code issuer} or {@code appBaseUrl} is blank, or if any of
   *     the three durations is not positive
   */
  public AuthProperties {
    requireNonBlank(issuer, "zarlania.auth.issuer");
    requirePositive(accessTokenTtl, "zarlania.auth.access-token-ttl");
    requirePositive(refreshFamilyLifetime, "zarlania.auth.refresh-family-lifetime");
    requirePositive(unverifiedAccountMaxAge, "zarlania.auth.unverified-account-max-age");
    requireNonBlank(appBaseUrl, "zarlania.auth.app-base-url");
  }

  /**
   * Treats an absent value and an empty one as the same failure, because for a text property they
   * are the same operator mistake: unset binds to null, set-but-empty binds to a blank string, and
   * neither is something a token or a link can be built from.
   */
  private static void requireNonBlank(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(property + " must not be blank");
    }
  }

  private static void requirePositive(Duration value, String property) {
    Objects.requireNonNull(value, property);
    if (!value.isPositive()) {
      throw new IllegalArgumentException(property + " must be positive, but was " + value);
    }
  }
}
