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
public class InvalidCredentialsException extends RuntimeException {}
