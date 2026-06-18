package com.zarlania.api.users;

/** Thrown when creating a user whose email already belongs to an existing account. */
public class EmailAlreadyExistsException extends RuntimeException {

  /**
   * Creates the exception for a conflicting email.
   *
   * @param email the email already in use
   */
  public EmailAlreadyExistsException(String email) {
    super("A user already exists with email: " + email);
  }
}
