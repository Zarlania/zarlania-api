package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zarlania.api.auth.AuthProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link JwtKeys} and {@link JwtService}. No Spring context: keys are built from a
 * freshly generated test PEM (or none, to exercise the ephemeral path) and time comes from a fixed
 * {@link Clock} so {@code exp - iat} is deterministic.
 */
class JwtServiceTest {

  private static final String ISSUER = "https://api.zarlania.test";
  private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
  private static final Instant ISSUED_AT = Instant.parse("2026-07-26T00:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(ISSUED_AT, ZoneOffset.UTC);
  private static final String TOKEN_KIND = "access";
  private static final String PRODUCTION_PROFILE = "production";
  private static final int TEST_KEY_SIZE_BITS = 2048;
  private static final List<String> PRIVATE_JWK_MEMBER_NAMES =
      List.of("d", "p", "q", "dp", "dq", "qi");

  @Test
  void mintedTokenHeaderIsRs256WithKidEqualToTheSigningKeyThumbprint() throws Exception {
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), "");
    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);

    SignedJWT signedJwt =
        SignedJWT.parse(jwtService.mint(UUID.randomUUID(), UUID.randomUUID(), TOKEN_KIND));

    assertThat(signedJwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(signedJwt.getHeader().getKeyID()).isEqualTo(jwtKeys.signingKey().getKeyID());
  }

  @Test
  void mintedTokenClaimsRoundTripIssuerSubjectOrganizationAndKind() throws Exception {
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), "");
    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    SignedJWT signedJwt = SignedJWT.parse(jwtService.mint(userId, organizationId, TOKEN_KIND));
    JWTClaimsSet claims = signedJwt.getJWTClaimsSet();

    assertThat(claims.getIssuer()).isEqualTo(ISSUER);
    assertThat(claims.getSubject()).isEqualTo(userId.toString());
    assertThat(claims.getStringClaim("org")).isEqualTo(organizationId.toString());
    assertThat(claims.getStringClaim("kind")).isEqualTo(TOKEN_KIND);
  }

  @Test
  void mintedTokenExpiryIsExactlyTheConfiguredTtlAfterIssuedAt() throws Exception {
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), "");
    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);

    SignedJWT signedJwt =
        SignedJWT.parse(jwtService.mint(UUID.randomUUID(), UUID.randomUUID(), TOKEN_KIND));
    JWTClaimsSet claims = signedJwt.getJWTClaimsSet();

    Duration lifetime =
        Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant());
    assertThat(lifetime).isEqualTo(ACCESS_TOKEN_TTL);
  }

  @Test
  void mintedTokenSignatureVerifiesAgainstThePublicJwkSet() throws Exception {
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), "");
    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);

    SignedJWT signedJwt =
        SignedJWT.parse(jwtService.mint(UUID.randomUUID(), UUID.randomUUID(), TOKEN_KIND));
    RSAKey publicKey = publicKeyFromJwkSet(jwtKeys, signedJwt.getHeader().getKeyID());

    assertThat(signedJwt.verify(new RSASSAVerifier(publicKey))).isTrue();
  }

  @Test
  void publicJwkSetContainsNoPrivateKeyMaterialForSigningOrRetiredKeys() throws Exception {
    KeyPair retiredKeyPair = generateTestKeyPair();
    String retiredPem =
        publicKeyPem(retiredKeyPair.getPublic()) + publicKeyPem(retiredKeyPair.getPublic());
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), retiredPem);

    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());

    Map<String, Object> jwksJson = jwtKeys.publicJwkSet().toJSONObject();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) jwksJson.get("keys");
    assertThat(keys).hasSize(3);
    keys.forEach(
        key -> assertThat(key.keySet()).doesNotContainAnyElementsOf(PRIVATE_JWK_MEMBER_NAMES));
  }

  @Test
  void blankRetiredPublicKeysPemYieldsNoRetiredKeys() throws Exception {
    AuthProperties authProperties = authProperties(generateTestPrivateKeyPem(), "");

    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());

    assertThat(jwtKeys.publicJwkSet().getKeys()).hasSize(1);
  }

  @Test
  void blankPrivateKeyPemInProductionFailsStartup() {
    AuthProperties authProperties = authProperties("", "");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(PRODUCTION_PROFILE);

    assertThatThrownBy(() -> new JwtKeys(authProperties, environment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void blankPrivateKeyPemOutsideProductionGeneratesAnEphemeralKeyThatStillSignsAndVerifies()
      throws Exception {
    AuthProperties authProperties = authProperties("", "");
    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);

    SignedJWT signedJwt =
        SignedJWT.parse(jwtService.mint(UUID.randomUUID(), UUID.randomUUID(), TOKEN_KIND));
    RSAKey publicKey = publicKeyFromJwkSet(jwtKeys, signedJwt.getHeader().getKeyID());

    assertThat(signedJwt.verify(new RSASSAVerifier(publicKey))).isTrue();
  }

  private static RSAKey publicKeyFromJwkSet(JwtKeys jwtKeys, String keyId) {
    return (RSAKey) jwtKeys.publicJwkSet().getKeyByKeyId(keyId);
  }

  private static AuthProperties authProperties(String privateKeyPem, String retiredPublicKeysPem) {
    return new AuthProperties(
        ISSUER,
        ACCESS_TOKEN_TTL,
        Duration.ofDays(30),
        Duration.ofDays(7),
        true,
        privateKeyPem,
        retiredPublicKeysPem,
        "https://zarlania.com");
  }

  // The spelling a PEM takes when it is set through a platform API, a .env file or `docker run
  // --env` rather than pasted into a dashboard: one line, with each break written as the two
  // characters backslash-n. Both key vars are documented as accepting it, and until the escapes
  // were stripped they survived into the Base64 body and made the strict decoder throw — inside
  // JwtKeys' constructor, so the whole service failed to start rather than one request failing.
  @Test
  void privateKeyPemWrittenOnOneLineWithEscapedNewlinesStillParses() throws Exception {
    String onOneLine = escapeLineBreaks(generateTestPrivateKeyPem());
    AuthProperties authProperties = authProperties(onOneLine, "");

    JwtKeys jwtKeys = new JwtKeys(authProperties, new MockEnvironment());
    JwtService jwtService = new JwtService(authProperties, jwtKeys, FIXED_CLOCK);

    SignedJWT signedJwt =
        SignedJWT.parse(jwtService.mint(UUID.randomUUID(), UUID.randomUUID(), TOKEN_KIND));
    assertThat(signedJwt.verify(new RSASSAVerifier(jwtKeys.signingKey().toRSAPublicKey())))
        .isTrue();
  }

  @Test
  void retiredPublicKeyPemWrittenOnOneLineWithEscapedNewlinesStillParses() throws Exception {
    KeyPair retired = generateTestKeyPair();
    String onOneLine = escapeLineBreaks(publicKeyPem(retired.getPublic()));

    JwtKeys jwtKeys =
        new JwtKeys(authProperties(generateTestPrivateKeyPem(), onOneLine), new MockEnvironment());

    // Two keys published: the current signing key and the retired one, so tokens minted before the
    // rotation still verify.
    assertThat(jwtKeys.publicJwkSet().getKeys()).hasSize(2);
  }

  private static String escapeLineBreaks(String pem) {
    return pem.replace("\n", "\\n");
  }

  private static String generateTestPrivateKeyPem() throws NoSuchAlgorithmException {
    KeyPair keyPair = generateTestKeyPair();
    String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    // The PEM header itself, not a secret; gitleaks flags the literal text regardless of context.
    return "-----BEGIN PRIVATE KEY-----\n"
        + base64
        + "\n-----END PRIVATE KEY-----\n"; // gitleaks:allow
  }

  private static String publicKeyPem(PublicKey publicKey) {
    String base64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
  }

  private static KeyPair generateTestKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(TEST_KEY_SIZE_BITS);
    return generator.generateKeyPair();
  }
}
