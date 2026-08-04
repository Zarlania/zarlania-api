package com.zarlania.api.auth.exceptions;

/**
 * Thrown when a registration names a username that already belongs to someone.
 *
 * <p>The one registration failure a caller is told about. An address already in use is deliberately
 * <em>not</em> an error — answering differently for it would let anyone test whether an address has
 * an account — whereas a username is public by nature, and refusing without saying why would leave
 * the caller retrying a name that can never be granted.
 */
public class UsernameTakenException extends RuntimeException {}
