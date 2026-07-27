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

  private static final String JWKS_PATH = "/.well-known/jwks.json";

  private final JwtKeys jwtKeys;

  @GetMapping(JWKS_PATH)
  public Map<String, Object> jwks() {
    return jwtKeys.publicJwkSet().toJSONObject();
  }
}
