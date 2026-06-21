package com.zarlania.api.users.exception;

import lombok.Getter;

/** Thrown when creating a user whose username (public handle) already belongs to an account. */
@Getter
public class UsernameAlreadyExistsException extends RuntimeException {

  /** The conflicting username. A public handle (not PII), so it is safe to surface. */
  private final String username;

  private UsernameAlreadyExistsException(String username, Throwable cause) {
    super("A user already exists with the username '" + username + "'", cause);
    this.username = username;
  }

  /**
   * Creates the exception for a conflicting username. Unlike the email equivalent, the handle is
   * included in the message because a username is a public handle, not PII.
   *
   * @param username the username already in use
   * @return an exception describing the conflict
   */
  public static UsernameAlreadyExistsException forUsername(String username) {
    return new UsernameAlreadyExistsException(username, null);
  }

  /**
   * Creates the exception for a conflicting username detected by the database, chaining the
   * persistence failure as the cause so its stack trace and DB context are preserved.
   *
   * @param username the username already in use
   * @param cause the underlying integrity violation
   * @return an exception describing the conflict
   */
  public static UsernameAlreadyExistsException forUsername(String username, Throwable cause) {
    return new UsernameAlreadyExistsException(username, cause);
  }
}
