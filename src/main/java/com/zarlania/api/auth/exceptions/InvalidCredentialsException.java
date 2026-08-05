package com.zarlania.api.auth.exceptions;

/**
 * Thrown when a login cannot be granted because the credentials do not check out — an unknown
 * identifier and a known identifier with the wrong password alike.
 *
 * <p>One exception for both cases on purpose, rather than a separate {@code
 * UnknownAccountException} that a handler might one day answer differently: the two must stay
 * indistinguishable to the caller, and the surest way to guarantee that is to leave the distinction
 * unrepresented. Timing parity is handled separately, where the decoy hash is paid.
 */
public final class InvalidCredentialsException extends RuntimeException {

  private static final String MESSAGE = "Login credentials were rejected";

  private InvalidCredentialsException() {
    super(MESSAGE);
  }

  /**
   * The only way to build one, and it takes no argument by design. A factory per cause — unknown
   * identifier, wrong password — would put the distinction this class exists to erase back into the
   * code, where a later handler could branch on it.
   */
  public static InvalidCredentialsException forRejectedLogin() {
    return new InvalidCredentialsException();
  }
}
