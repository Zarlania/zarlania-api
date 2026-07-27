package com.zarlania.api.auth.services;

/**
 * String values for the access token's {@code kind} claim, distinguishing what kind of session a
 * token represents.
 *
 * <p>Only {@link #USER} exists today. An {@code IMPERSONATION} kind (support staff acting as a
 * user) and a {@code SERVICE} kind (machine-to-machine callers) are reserved by a later spec — they
 * are deliberately not added here, since nothing yet mints or checks them.
 */
public final class TokenKinds {

  public static final String USER = "user";

  private TokenKinds() {}
}
