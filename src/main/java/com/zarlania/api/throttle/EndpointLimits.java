package com.zarlania.api.throttle;

import java.util.Optional;

/**
 * One endpoint's two throttle limits, both counted over {@link ThrottleProperties#window()}. Bound
 * from a {@code zarlania.throttle.endpoints} entry, keyed by the name in {@link
 * Throttled#endpoint()}.
 *
 * <p><strong>Two more buckets belong here and are not built yet.</strong> Every throttled endpoint
 * today is public, so the only identities available are the caller's address and whatever account
 * the request body names. An authenticated endpoint carries better ones already proved: the access
 * token's {@code sub} is the user, and its {@code org} claim is the organization, both read without
 * trusting anything the caller typed. When the first throttled authenticated endpoint arrives it
 * should get:
 *
 * <ul>
 *   <li>a <strong>per-user</strong> bucket keyed on {@code sub}, which bounds one account across
 *       every address it calls from — the account-identifier bucket below only approximates this,
 *       and only where the body names an account at all;
 *   <li>a <strong>per-organization</strong> bucket keyed on {@code org}, so a shared organization
 *       gets one budget rather than one per member. This matters only once general (multi-member)
 *       organizations exist, and is expected to land with them.
 * </ul>
 *
 * <p>Neither is added here in advance: a limit no endpoint consumes is configuration that cannot be
 * wrong yet and code nothing exercises. What the shape needs to allow for is that {@code
 * accountLimit} stops being the only optional bucket, so reaching for {@code
 * accountLimitIfPresent()} as the general test of "does this endpoint have a second bucket" is what
 * would have to change.
 *
 * @param limit requests allowed per client IP
 * @param accountLimit requests allowed per account named in the request, across every IP; absent
 *     for endpoints that name no account, such as refresh (which carries only a cookie) and the
 *     CSRF token endpoint (which carries nothing)
 */
public record EndpointLimits(int limit, Integer accountLimit) {

  /**
   * Rejects a limit that would close the endpoint rather than throttle it.
   *
   * <p>{@link InMemoryRateLimiter} permits a request when the running count for the window is at or
   * below the limit, so a limit of zero or less is exceeded by the first caller and refuses every
   * request from then on. Nothing downstream reports that as a misconfiguration — the endpoint
   * simply answers 429 forever, which reads as a throttle working hard rather than one set wrong.
   * Zero is also what an empty value in the YAML binds to.
   *
   * <p>A null {@code accountLimit} is left alone, because absent and non-positive mean different
   * things: absent is how an endpoint that names no account is configured, and several are. Zero
   * would be an account bucket that refuses everybody.
   *
   * <p>The messages name the configuration keys rather than the record components. The endpoint
   * they belong to is not knowable here — this record is one value in a map keyed by endpoint name,
   * and the binder builds it before {@link ThrottleProperties} sees which key it sat under — so
   * whoever reads one is told which setting is wrong and left to find it under the endpoint they
   * were editing.
   *
   * @throws IllegalArgumentException if {@code limit} is not at least one, or if {@code
   *     accountLimit} is present and not at least one
   */
  public EndpointLimits {
    if (limit < 1) {
      throw new IllegalArgumentException(
          "zarlania.throttle.endpoints limit must be at least 1, but was " + limit);
    }
    if (accountLimit != null && accountLimit < 1) {
      throw new IllegalArgumentException(
          "zarlania.throttle.endpoints account-limit must be at least 1 when set, but was "
              + accountLimit);
    }
  }

  /** The per-account limit, empty when this endpoint has no account bucket. */
  public Optional<Integer> accountLimitIfPresent() {
    return Optional.ofNullable(accountLimit);
  }
}
