package com.zarlania.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.services.JwtKeys;
import com.zarlania.api.auth.services.JwtService;
import com.zarlania.api.auth.services.TokenClaims;
import com.zarlania.api.auth.services.TokenKind;
import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.EndToEndTestBase;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exercises the real filter chain end to end: no security-test shortcuts (no
 * {@code @WithMockUser}), so a request either carries a token that survives {@link JwtDecoder} plus
 * {@link SecurityConfig}'s authentication converter, or it does not.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class SecurityConfigEndToEndTest extends EndToEndTestBase {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final Duration HAND_MINTED_TOKEN_TTL = Duration.ofMinutes(15);
  // application.yml's default for zarlania.cors.allowed-origins, which this test does not override.
  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private final UserService userService;
  private final OrganizationService organizationService;
  private final JwtService jwtService;
  private final JwtKeys jwtKeys;
  private final AuthProperties authProperties;

  @Test
  void meWithoutATokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void meWithAValidTokenReturnsTheSeededUserAndPersonalOrganization() throws Exception {
    User user = userService.createUnverified("me@example.com", "meuser");
    Organization organization =
        organizationService.createPersonalOrganization(user.id(), "meuser's Space");
    String token = jwtService.mint(user.id(), organization.id(), TokenKind.USER);

    mockMvc
        .perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value("meuser"))
        .andExpect(jsonPath("$.organization.type").value("PERSONAL"));
  }

  @Test
  void meWithATamperedSignatureTokenIsUnauthorizedNotServerError() throws Exception {
    User user = userService.createUnverified("tampered@example.com", "tampereduser");
    Organization organization =
        organizationService.createPersonalOrganization(user.id(), "tampereduser's Space");
    String token = jwtService.mint(user.id(), organization.id(), TokenKind.USER);

    mockMvc
        .perform(
            get("/users/me")
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + tamperSignature(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void meWithAMalformedTokenIsUnauthorizedNotServerError() throws Exception {
    mockMvc
        .perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + "not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  // Neither test above reaches SecurityConfig's authentication converter: a tampered
  // signature and a non-JWT string both fail earlier, inside JwtDecoder.decode(). This test
  // signs a token that passes decode() cleanly but is missing a claim the converter requires,
  // so it exercises the converter's catch (IllegalArgumentException) -> InvalidBearerTokenException
  // branch specifically — the difference between that branch answering 401 and an unhandled
  // exception surfacing as 500.
  @Test
  void meWithATokenMissingTheOrganizationClaimIsUnauthorizedNotServerError() throws Exception {
    String token = mintTokenMissingClaims(true, false);

    MvcResult result =
        mockMvc
            .perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void meWithATokenMissingTheSubjectClaimIsUnauthorizedNotServerError() throws Exception {
    String token = mintTokenMissingClaims(false, true);

    MvcResult result =
        mockMvc
            .perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void jwksEndpointIsPubliclyReachableAndPublishesAKeysArray() throws Exception {
    mockMvc
        .perform(get("/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys").isArray());
  }

  @Test
  void actuatorHealthIsPubliclyReachableWithoutAToken() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  // permitAll exempts a path from the authorization check, not from the filters ahead of it, so
  // BearerTokenAuthenticationFilter would otherwise fail a public request purely for carrying a
  // dead Authorization header. That is the ordinary state of a browser client with one global
  // interceptor the moment its access token expires — and POST /auth/refresh, the request whose
  // whole job is to recover from exactly that, is a public path. Every public path is checked
  // rather than only refresh, because the rule is about the chain, not about one route.
  @ParameterizedTest(name = "{0} ignores a dead bearer token")
  @ValueSource(strings = {"/auth/csrf", "/.well-known/jwks.json", "/actuator/health"})
  void aPublicPathIgnoresAnExpiredOrMalformedBearerTokenInsteadOfRejectingTheRequest(String path)
      throws Exception {
    mockMvc
        .perform(get(path).header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + "not-a-jwt"))
        .andExpect(status().isOk());
  }

  // Retry-After is not CORS-safelisted, so without setExposedHeaders the browser client reads a 429
  // it cannot act on: ThrottleAspect computes how long the window still needs, and the header is
  // the only place that number is published.
  @Test
  void aCrossOriginResponseExposesRetryAfterSoAThrottledClientCanReadIt() throws Exception {
    mockMvc
        .perform(get("/auth/csrf").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.RETRY_AFTER));
  }

  // Flips the first character of the signature segment so the claims and header stay
  // well-formed but the signature no longer verifies against the signing key.
  private static String tamperSignature(String jwt) {
    int lastDot = jwt.lastIndexOf('.');
    String signature = jwt.substring(lastDot + 1);
    char flipped = signature.charAt(0) == 'A' ? 'B' : 'A';
    return jwt.substring(0, lastDot + 1) + flipped + signature.substring(1);
  }

  // Signs directly with Nimbus and JwtKeys' real signing key, instead of going through
  // JwtService.mint, so the caller can omit a claim while everything else about the token
  // (signature, timestamps, issuer) stays valid enough to clear JwtDecoder.decode(). The issuer
  // matters: the decoder pins it, so a token without one would be refused before the converter
  // these tests exist to exercise ever sees it.
  private String mintTokenMissingClaims(boolean includeSubject, boolean includeOrganization)
      throws JOSEException {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(authProperties.issuer())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(HAND_MINTED_TOKEN_TTL)));
    if (includeSubject) {
      claims.subject(UUID.randomUUID().toString());
    }
    if (includeOrganization) {
      claims.claim(TokenClaims.ORGANIZATION, UUID.randomUUID().toString());
    }

    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(jwtKeys.signingKey().getKeyID())
                .build(),
            claims.build());
    jwt.sign(new RSASSASigner(jwtKeys.signingKey()));
    return jwt.serialize();
  }
}
