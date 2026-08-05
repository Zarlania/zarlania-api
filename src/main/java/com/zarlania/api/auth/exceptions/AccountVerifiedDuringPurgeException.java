package com.zarlania.api.auth.exceptions;

import java.util.UUID;

/**
 * Thrown when the account being purged turns out to have been verified after the sweep listed it.
 * Its only job is to roll the purge transaction back — the credential, token and organization rows
 * deleted before the guard ran all belong to an account that is now live, so none of those deletes
 * may stand.
 *
 * <p>Deliberately carries no {@link com.zarlania.api.errors.ErrorCode} and reaches no exception
 * handler: no request is in flight, and nothing about this is a failure. It is the expected outcome
 * of a race the sweep is designed to lose safely, which is why the cleanup catches it separately
 * and logs it below error level.
 */
public final class AccountVerifiedDuringPurgeException extends RuntimeException {

  private AccountVerifiedDuringPurgeException(String message) {
    super(message);
  }

  /**
   * @param userId names the account in the message, since this is only ever read in logs
   */
  public static AccountVerifiedDuringPurgeException forUser(UUID userId) {
    return new AccountVerifiedDuringPurgeException(
        "Account " + userId + " was verified before its purge could complete");
  }
}
