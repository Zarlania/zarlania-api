package com.zarlania.api.auth.exceptions;

import java.util.UUID;

/**
 * Thrown when a refresh token that was already redeemed is presented again — treated as evidence
 * the token was stolen, so the whole family is revoked before this is thrown.
 */
public final class ReusedRefreshTokenException extends RuntimeException {

  private ReusedRefreshTokenException(String message) {
    super(message);
  }

  /**
   * @param familyId the token family revoked in response. A UUID, so it is safe to log, and it is
   *     what ties this to the {@code REFRESH_TOKEN_REUSE} warning already emitted at the throw
   *     site.
   */
  public static ReusedRefreshTokenException forRevokedFamily(UUID familyId) {
    return new ReusedRefreshTokenException(
        "Refresh token replayed; family " + familyId + " was revoked");
  }
}
