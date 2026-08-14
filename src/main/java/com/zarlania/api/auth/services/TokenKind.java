package com.zarlania.api.auth.services;

import java.util.Arrays;

/**
 * What kind of session an access token represents, carried in its {@code kind} claim.
 *
 * <p>Only {@link #USER} exists today. An {@code IMPERSONATION} kind (support staff acting as a
 * user) and a {@code SERVICE} kind (machine-to-machine callers) are reserved by a later spec — they
 * are deliberately not added here, since nothing yet mints or checks them.
 *
 * <p>The wire string is held explicitly rather than taken from {@link #name()}. The claim value is
 * published contract — {@code zarlania-app} and any other verifier read it — so renaming a constant
 * here must not be able to change what appears in a token. It also keeps the claim lowercase
 * without every read site having to fold case.
 */
public enum TokenKind {
  /** A token minted for a person signing in, which is every token this service mints today. */
  USER("user");

  private final String value;

  TokenKind(String value) {
    this.value = value;
  }

  /**
   * Resolves the {@code kind} claim of an inbound token.
   *
   * @param value the claim as it appeared in the token, which is caller-controlled text
   * @throws IllegalArgumentException if no kind carries that value, including when {@code value} is
   *     null — an unrecognised kind is a token this service will not honour, and rejecting it here
   *     is what stops an unknown kind being treated as {@link #USER}
   */
  public static TokenKind fromValue(String value) {
    return Arrays.stream(values())
        .filter(kind -> kind.value.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown token kind: " + value));
  }

  /** The string written to and read from the {@code kind} claim, such as {@code user}. */
  public String value() {
    return value;
  }
}
