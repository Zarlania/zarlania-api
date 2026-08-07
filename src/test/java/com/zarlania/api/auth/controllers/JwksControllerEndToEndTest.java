package com.zarlania.api.auth.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.EndToEndTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What the JWK set endpoint publishes, and what it must never publish.
 *
 * <p>The whole point of the route is that anything verifying a token can fetch it without holding
 * one, so it is deliberately public — which makes "no private key material, ever" the assertion
 * that matters most here rather than a nicety.
 */
class JwksControllerEndToEndTest extends EndToEndTestBase {

  @Test
  void publishesAKeySetReachableWithoutAnyCredential() throws Exception {
    mockMvc
        .perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys").isArray())
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].kid").isNotEmpty());
  }

  // The RSA private-key parameters, by their JWK names. Any one of them appearing here would mean
  // the service had published the key it signs with — every token ever minted would be forgeable.
  @ParameterizedTest(name = "no {0} parameter is published")
  @ValueSource(strings = {"d", "p", "q", "dp", "dq", "qi"})
  void neverPublishesAnyPrivateKeyParameter(String parameter) throws Exception {
    mockMvc
        .perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys[*]." + parameter).isEmpty());
  }
}
