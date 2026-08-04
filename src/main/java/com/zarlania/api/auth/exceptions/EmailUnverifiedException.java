package com.zarlania.api.auth.exceptions;

/**
 * Thrown when a password check succeeded but the account's address was never proved, so no session
 * may be minted.
 *
 * <p>Distinct from {@link InvalidCredentialsException} deliberately: the caller has demonstrated
 * they know the password, so telling them the account exists but needs verifying reveals nothing
 * they could not already confirm, and leaving them on a generic failure would strand them with no
 * idea what to do next.
 */
public class EmailUnverifiedException extends RuntimeException {}
