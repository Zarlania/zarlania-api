package com.zarlania.api.auth.services;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Mints short-lived RS256 access tokens signed by {@link JwtKeys#signingKey()}. */
@Service
@RequiredArgsConstructor
public class JwtService {

  private final AuthProperties authProperties;
  private final JwtKeys jwtKeys;
  private final Clock clock;

  /**
   * Mints a signed access token for one account in one organization.
   *
   * @param kind what the token proves; written to the {@code kind} claim as {@link
   *     TokenKind#value()}
   * @return a compact-serialized RS256 JWT, verifiable against the published JWK set
   */
  public String mint(UUID userId, UUID organizationId, TokenKind kind) {
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(authProperties.issuer())
            .subject(userId.toString())
            .claim(TokenClaims.ORGANIZATION, organizationId.toString())
            .claim(TokenClaims.KIND, kind.value())
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(authProperties.accessTokenTtl())))
            .build();
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256)
                  .keyID(jwtKeys.signingKey().getKeyID())
                  .build(),
              claims);
      jwt.sign(new RSASSASigner(jwtKeys.signingKey()));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException("JWT signing failed", exception);
    }
  }
}
