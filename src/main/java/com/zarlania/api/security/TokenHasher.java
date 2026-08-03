package com.zarlania.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Raw bearer secrets are never persisted; only their SHA-256 is stored or looked up. */
public final class TokenHasher {

  private static final int TOKEN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenHasher() {}

  /**
   * Mints a fresh random token, URL-safe and unpadded so it can be dropped into a link untouched.
   *
   * <p>Drawn from a {@link java.security.SecureRandom}: these become verification links and refresh
   * cookies, so a predictable value is a forgeable credential.
   */
  public static String newUrlSafeToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Hashes a token for storage, so a database disclosure yields nothing redeemable.
   *
   * <p>A plain SHA-256, not a password hash: these tokens are already full-entropy random values,
   * so there is no dictionary to slow an attacker down against and the deliberate cost of Argon2
   * would buy nothing while making every lookup expensive.
   */
  public static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
