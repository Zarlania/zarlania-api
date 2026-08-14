package com.zarlania.api.throttle;

import java.util.Locale;

/**
 * Builds the bucket keys the throttle counts against. Two shapes exist, {@code <endpoint>:<client
 * ip>} and {@code <endpoint>:acct:<identifier>}, and an address can never collide with the account
 * form because no IP literal starts with {@code acct:}.
 */
public final class ThrottleKeys {

  private static final String CLIENT_INFIX = ":";
  private static final String ACCOUNT_INFIX = ":acct:";
  private static final int MAX_ACCOUNT_IDENTIFIER_LENGTH = 100;

  private ThrottleKeys() {}

  /**
   * Keys a bucket on where a request came from.
   *
   * @param endpoint the throttled endpoint's name
   * @param clientIp the caller's address, as resolved by {@code http.ClientIpResolver}
   */
  public static String forClient(String endpoint, String clientIp) {
    return endpoint + CLIENT_INFIX + clientIp;
  }

  /**
   * Keys a bucket on the account a request names, independently of where it came from. Per-IP
   * limits alone leave credential stuffing distributed across many addresses against one known
   * username bounded only by how many addresses the attacker controls.
   *
   * <p>The bucket exists for whatever string the caller supplied, whether or not an account matches
   * it, which is what keeps this out of the account-enumeration channel: a 429 says the identifier
   * has been tried a lot, never that it is real.
   *
   * @param endpoint the throttled endpoint's name
   * @param accountIdentifier the email or username the request names, in whatever spelling the
   *     caller sent
   */
  public static String forAccount(String endpoint, String accountIdentifier) {
    return endpoint + ACCOUNT_INFIX + normalize(accountIdentifier);
  }

  /**
   * Folds the spellings of one account onto one key. Email and username are {@code citext} columns,
   * so Postgres treats "Bob" and "bob" as the same account and keying on the raw string would hand
   * an attacker a fresh bucket per spelling. {@link Locale#ROOT} rather than the default locale, so
   * the mapping cannot shift with the server's locale.
   *
   * <p>Truncated because the identifier fields are only {@code @NotBlank} — a caller could
   * otherwise mint arbitrarily long keys in the limiter's map. Sharing a bucket between two
   * accounts whose first 100 characters match only ever makes the limit stricter, never weaker.
   */
  private static String normalize(String accountIdentifier) {
    String normalized = accountIdentifier.trim().toLowerCase(Locale.ROOT);
    return normalized.length() <= MAX_ACCOUNT_IDENTIFIER_LENGTH
        ? normalized
        : normalized.substring(0, MAX_ACCOUNT_IDENTIFIER_LENGTH);
  }
}
