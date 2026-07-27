package com.zarlania.api.common.throttle;

/**
 * Fixed-window request throttle keyed by an arbitrary string — an endpoint name plus client
 * identity, in practice. {@link InMemoryRateLimiter} is the only implementation today; the
 * interface stays this narrow so a future Redis-backed implementation — needed once the API runs on
 * more than one instance, since in-memory counters neither survive a restart nor coordinate across
 * processes — can drop in without any caller changing.
 */
public interface RateLimiter {

  boolean tryConsume(String key, int limit);
}
