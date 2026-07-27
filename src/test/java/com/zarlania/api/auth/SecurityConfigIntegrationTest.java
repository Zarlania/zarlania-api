package com.zarlania.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.auth.services.JwtService;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
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
  private static final String ACCESS_TOKEN_KIND = "access";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final MockMvc mockMvc;
  private final UserService userService;
  private final OrganizationService organizationService;
  private final JwtService jwtService;

  @Test
  void meWithoutATokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void meWithAValidTokenReturnsTheSeededUserAndPersonalOrganization() throws Exception {
    UserDto user = userService.createUnverified("me@example.com", "meuser");
    OrganizationDto org =
        organizationService.createPersonalOrganization(user.id(), "meuser's Space");
    String token = jwtService.mint(user.id(), org.id(), ACCESS_TOKEN_KIND);

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
    String token = jwtService.mint(user.id(), org.id(), ACCESS_TOKEN_KIND);

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
}
