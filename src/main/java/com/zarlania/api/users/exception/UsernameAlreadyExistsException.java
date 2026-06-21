package com.zarlania.api.users.exception;

import lombok.Getter;

/** Thrown when creating a user whose username (public handle) already belongs to an account. */
@Getter
public class UsernameAlreadyExistsException extends RuntimeException {

  /** The conflicting username. A public handle (not PII), so it is safe to surface. */
  private final String username;

  private UsernameAlreadyExistsException(String username) {
    super("A user already exists with the username '" + username + "'");
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
    return new UsernameAlreadyExistsException(username);
  }
}
