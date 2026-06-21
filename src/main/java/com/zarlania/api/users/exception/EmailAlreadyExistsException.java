package com.zarlania.api.users.exception;

import lombok.Getter;

/** Thrown when creating a user whose email already belongs to an existing account. */
@Getter
public class EmailAlreadyExistsException extends RuntimeException {

  /** The conflicting email, kept as structured data and never embedded in the message. */
  private final String email;

  private EmailAlreadyExistsException(String email, Throwable cause) {
    super("A user already exists with the given email", cause);
    this.email = email;
  }

  /**
   * Creates the exception for a conflicting email. The email is retained via {@link #getEmail()}
   * for callers that need it, but is deliberately omitted from the message to avoid leaking the
   * address into logs or error responses.
   *
   * @param email the email already in use
   * @return an exception describing the conflict
   */
  public static EmailAlreadyExistsException forEmail(String email) {
    return new EmailAlreadyExistsException(email, null);
  }

  /**
   * Creates the exception for a conflicting email detected by the database, chaining the
   * persistence failure as the cause so its stack trace and DB context are preserved.
   *
   * @param email the email already in use
   * @param cause the underlying integrity violation
   * @return an exception describing the conflict
   */
  public static EmailAlreadyExistsException forEmail(String email, Throwable cause) {
    return new EmailAlreadyExistsException(email, cause);
  }
}
