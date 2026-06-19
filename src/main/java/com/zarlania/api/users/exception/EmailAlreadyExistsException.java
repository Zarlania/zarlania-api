package com.zarlania.api.users.exception;

import lombok.Getter;

/** Thrown when creating a user whose email already belongs to an existing account. */
@Getter
public class EmailAlreadyExistsException extends RuntimeException {

  /** The conflicting email, kept as structured data and never embedded in the message. */
  private final String email;

  private EmailAlreadyExistsException(String email) {
    super("A user already exists with the given email");
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
    return new EmailAlreadyExistsException(email);
  }
}
