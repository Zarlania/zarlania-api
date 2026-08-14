package com.zarlania.api.auth.controllers;

import com.zarlania.api.errors.ErrorCode;

/**
 * Every error the auth domain can answer with. Lives in {@code controllers} because a code is an
 * HTTP fact: the services throw exceptions that name a cause, and the decision about which status
 * and which published string that cause becomes is made here, beside {@link AuthExceptionHandler}
 * and the controller that raises the rest.
 *
 * <p>Two of these are deliberately reachable from more than one cause. {@link #INVALID_CREDENTIALS}
 * answers a wrong password, an unknown identifier, a refused refresh token and a missing refresh
 * cookie alike — telling those apart is exactly what an attacker wants — so the shared code is the
 * point rather than an oversight.
 */
public enum AuthErrorCode implements ErrorCode {
  /** A username someone else already holds. The one registration failure a caller is told about. */
  USERNAME_TAKEN("auth.username-taken", 409),

  /** The password was right but the address was never proved, so no session may be minted. */
  EMAIL_UNVERIFIED("auth.email-unverified", 403),

  /** Any failure to authenticate, whatever the real cause. See this enum's own documentation. */
  INVALID_CREDENTIALS("auth.invalid-credentials", 401),

  /**
   * A verification token that is unknown, expired or already consumed — the three are one answer.
   */
  INVALID_TOKEN("auth.invalid-token", 400);

  private final String code;
  private final int status;

  AuthErrorCode(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public int getStatus() {
    return status;
  }
}
