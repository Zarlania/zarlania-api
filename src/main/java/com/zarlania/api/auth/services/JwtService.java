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

  private static final String ORGANIZATION_CLAIM = "org";
  private static final String KIND_CLAIM = "kind";

  private final AuthProperties authProperties;
  private final JwtKeys jwtKeys;
  private final Clock clock;

  public String mint(UUID userId, UUID organizationId, String kind) {
    Instant now = clock.instant();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(authProperties.issuer())
            .subject(userId.toString())
            .claim(ORGANIZATION_CLAIM, organizationId.toString())
            .claim(KIND_CLAIM, kind)
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
    } catch (JOSEException e) {
      throw new IllegalStateException("JWT signing failed", e);
    }
  }
}
