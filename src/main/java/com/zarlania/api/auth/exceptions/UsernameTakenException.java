package com.zarlania.api.auth.exceptions;

/**
 * Thrown when a registration names a username that already belongs to someone.
 *
 * <p>The one registration failure a caller is told about. An address already in use is deliberately
 * <em>not</em> an error — answering differently for it would let anyone test whether an address has
 * an account — whereas a username is public by nature, and refusing without saying why would leave
 * the caller retrying a name that can never be granted.
 */
public final class UsernameTakenException extends RuntimeException {

  private UsernameTakenException(String message) {
    super(message);
  }

  /**
   * @param username named in the message because a username is public by nature — unlike an address
   *     — so it is safe to log and is the only thing that makes the line actionable
   */
  public static UsernameTakenException forUsername(String username) {
    return new UsernameTakenException("Username already taken: " + username);
  }
}
