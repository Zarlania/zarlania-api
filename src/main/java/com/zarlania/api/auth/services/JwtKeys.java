package com.zarlania.api.auth.services;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.zarlania.api.auth.AuthProperties;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Holds the RSA key pair that signs access tokens plus any retired public keys still needed to
 * verify tokens minted before a rotation. Keys are resolved once at construction time — signing
 * every token during the process's lifetime with the same key, and the same {@code kid}, is what
 * makes JWKS-based verification in Task 9 work.
 */
@Component
public final class JwtKeys {

  private static final String RSA_ALGORITHM = "RSA";
  private static final int EPHEMERAL_KEY_SIZE_BITS = 2048;
  private static final String PRODUCTION_PROFILE = "production";
  // The PEM header itself, not a secret; gitleaks flags the literal text regardless of context.
  private static final String PRIVATE_KEY_BEGIN_MARKER =
      "-----BEGIN PRIVATE KEY-----"; // gitleaks:allow
  private static final String PRIVATE_KEY_END_MARKER = "-----END PRIVATE KEY-----";
  private static final String PUBLIC_KEY_BEGIN_MARKER = "-----BEGIN PUBLIC KEY-----";
  private static final String PUBLIC_KEY_END_MARKER = "-----END PUBLIC KEY-----";

  private final RSAKey signingKey;
  private final JWKSet publicJwkSet;

  public JwtKeys(AuthProperties authProperties, Environment environment) {
    this.signingKey = resolveSigningKey(authProperties, environment);
    this.publicJwkSet = buildPublicJwkSet(signingKey, authProperties.jwtRetiredPublicKeysPem());
  }

  public RSAKey signingKey() {
    return signingKey;
  }

  public JWKSet publicJwkSet() {
    return publicJwkSet;
  }

  private static RSAKey resolveSigningKey(AuthProperties authProperties, Environment environment) {
    String pem = authProperties.jwtPrivateKeyPem();
    if (pem != null && !pem.isBlank()) {
      return signingKeyFromPem(pem);
    }
    if (environment.matchesProfiles(PRODUCTION_PROFILE)) {
      throw new IllegalStateException(
          "zarlania.auth.jwt-private-key-pem (JWT_PRIVATE_KEY) must be set in production");
    }
    return generateEphemeralSigningKey();
  }

  private static RSAKey signingKeyFromPem(String pem) {
    byte[] keyBytes = decodeBase64Body(pem, PRIVATE_KEY_BEGIN_MARKER, PRIVATE_KEY_END_MARKER);
    try {
      KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
      RSAPrivateKey privateKey =
          (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
      RSAPublicKey publicKey = derivePublicKey(keyFactory, privateKey);
      return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
      throw new IllegalStateException("Failed to parse the configured JWT private key", e);
    }
  }

  private static RSAPublicKey derivePublicKey(KeyFactory keyFactory, RSAPrivateKey privateKey)
      throws InvalidKeySpecException {
    if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
      // A public exponent only exists on the CRT form of an RSA private key, so
      // there is no way to derive the matching public key from a plain one.
      throw new IllegalStateException(
          "Configured JWT private key is not in RSA CRT form; cannot derive its public key");
    }
    RSAPublicKeySpec publicKeySpec =
        new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
    return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
  }

  private static RSAKey generateEphemeralSigningKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
      generator.initialize(EPHEMERAL_KEY_SIZE_BITS);
      KeyPair keyPair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
          .privateKey(keyPair.getPrivate())
          .keyIDFromThumbprint()
          .build();
    } catch (NoSuchAlgorithmException | JOSEException e) {
      throw new IllegalStateException("Failed to generate an ephemeral JWT signing key", e);
    }
  }

  private static JWKSet buildPublicJwkSet(RSAKey signingKey, String retiredPublicKeysPem) {
    List<JWK> keys = new ArrayList<>();
    keys.add(signingKey.toPublicJWK());
    keys.addAll(retiredPublicKeys(retiredPublicKeysPem));
    return new JWKSet(keys);
  }

  private static List<RSAKey> retiredPublicKeys(String retiredPublicKeysPem) {
    if (retiredPublicKeysPem == null || retiredPublicKeysPem.isBlank()) {
      return List.of();
    }
    List<RSAKey> keys = new ArrayList<>();
    for (String block : retiredPublicKeysPem.split(PUBLIC_KEY_BEGIN_MARKER)) {
      if (!block.isBlank()) {
        keys.add(retiredPublicKeyFromBlock(block));
      }
    }
    return keys;
  }

  private static RSAKey retiredPublicKeyFromBlock(String block) {
    // The BEGIN marker is already gone: retiredPublicKeys() split the PEM on it.
    byte[] keyBytes = decodeBase64Body(block.replace(PUBLIC_KEY_END_MARKER, ""));
    try {
      KeyFactory keyFactory = KeyFactory.getInstance(RSA_ALGORITHM);
      PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
      return new RSAKey.Builder((RSAPublicKey) publicKey).keyIDFromThumbprint().build();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
      throw new IllegalStateException("Failed to parse a retired JWT public key", e);
    }
  }

  private static byte[] decodeBase64Body(String pem, String beginMarker, String endMarker) {
    return decodeBase64Body(pem.replace(beginMarker, "").replace(endMarker, ""));
  }

  private static byte[] decodeBase64Body(String base64WithMarkersRemoved) {
    // Env vars carry PEM blocks with either literal or escaped newlines depending
    // on how the platform injects them, so every whitespace character is
    // stripped rather than assuming one particular line-break style.
    String base64 = base64WithMarkersRemoved.replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
  }
}
