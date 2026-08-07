package com.zarlania.api.auth.exceptions;

import java.util.UUID;

/**
 * Thrown when a password check succeeded but the account's address was never proved, so no session
 * may be minted.
 *
 * <p>Distinct from {@link InvalidCredentialsException} deliberately: the caller has demonstrated
 * they know the password, so telling them the account exists but needs verifying reveals nothing
 * they could not already confirm, and leaving them on a generic failure would strand them with no
 * idea what to do next.
 */
public final class EmailUnverifiedException extends RuntimeException {

  private EmailUnverifiedException(String message) {
    super(message);
  }

  /**
   * @param userId names the account by id rather than by address, since this message is only ever
   *     read in logs and an address there identifies a person
   */
  public static EmailUnverifiedException forUser(UUID userId) {
    return new EmailUnverifiedException("Account " + userId + " has not verified its email");
  }
}
