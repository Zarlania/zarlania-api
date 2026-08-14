package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.services.JwtKeys;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public half of the signing key, plus any retired keys, as a JWK set. */
@RestController
@RequiredArgsConstructor
public class JwksController {

  private final JwtKeys jwtKeys;

  /**
   * Publishes the JWK set a client needs to verify an access token this service minted.
   *
   * <p>Public keys only, and public by design: it carries nothing secret, and anything that
   * verifies a token has to be able to fetch it without holding one.
   */
  @GetMapping("/.well-known/jwks.json")
  public Map<String, Object> jwks() {
    return jwtKeys.publicJwkSet().toJSONObject();
  }
}
