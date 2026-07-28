package com.zarlania.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.zarlania.api.auth.services.TokenKinds;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the real filter chain end to end: no security-test shortcuts (no
 * {@code @WithMockUser}), so a request either carries a token that survives {@link JwtDecoder} plus
 * {@link SecurityConfig}'s authentication converter, or it does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class SecurityConfigIntegrationTest {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final Duration HAND_MINTED_TOKEN_TTL = Duration.ofMinutes(15);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;
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
    UserDto user = userService.createUnverified("me@example.com", "meuser");
    OrganizationDto org =
        organizationService.createPersonalOrganization(user.id(), "meuser's Space");
    String token = jwtService.mint(user.id(), org.id(), TokenKinds.USER);

    mockMvc
        .perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.username").value("meuser"))
        .andExpect(jsonPath("$.organization.type").value("PERSONAL"));
  }

  @Test
  void meWithATamperedSignatureTokenIsUnauthorizedNotServerError() throws Exception {
    UserDto user = userService.createUnverified("tampered@example.com", "tampereduser");
    OrganizationDto org =
        organizationService.createPersonalOrganization(user.id(), "tampereduser's Space");
    String token = jwtService.mint(user.id(), org.id(), TokenKinds.USER);

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
