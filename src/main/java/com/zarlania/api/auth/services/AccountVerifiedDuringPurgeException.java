package com.zarlania.api.auth.services;

import java.util.UUID;

/**
 * Thrown by {@link UnverifiedAccountPurger} when the account it was purging turns out to have been
 * verified after the sweep listed it. Its only job is to roll the purge transaction back — the
 * credential, token and organization rows deleted before the guard ran all belong to an account
 * that is now live, so none of those deletes may stand.
 *
 * <p>Deliberately not an {@code ApiException}: no request is in flight, and nothing about this is a
 * failure. It is the expected outcome of a race the sweep is designed to lose safely, which is why
 * {@link UnverifiedAccountCleanup} catches it separately and logs it below error level.
 */
class AccountVerifiedDuringPurgeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  AccountVerifiedDuringPurgeException(UUID userId) {
    super("Account " + userId + " was verified before its purge could complete");
  }
}
