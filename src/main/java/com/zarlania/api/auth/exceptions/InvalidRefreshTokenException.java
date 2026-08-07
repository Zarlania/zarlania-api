package com.zarlania.api.auth.exceptions;

/** Thrown when a refresh token is unknown, already revoked, or its family has expired. */
public final class InvalidRefreshTokenException extends RuntimeException {

  private InvalidRefreshTokenException(String message) {
    super(message);
  }

  /**
   * The token itself is never passed in, and no factory offers a way to. A refresh token is a live
   * credential until it is redeemed, and an exception message is the one string in the system
   * guaranteed to reach a log.
   */
  public static InvalidRefreshTokenException forRejectedToken() {
    return new InvalidRefreshTokenException("Refresh token is unknown, revoked, or expired");
  }
}
