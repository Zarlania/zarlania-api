package com.zarlania.api.throttle;

import com.zarlania.api.errors.ErrorCode;

/** The error code {@link ThrottleAspect} answers with when a bucket is exhausted. */
public enum ThrottleErrorCode implements ErrorCode {
  /**
   * Too many requests for one of this endpoint's buckets. The response carries {@code Retry-After}
   * saying for how long, which is why the aspect raises it with response headers attached.
   *
   * <p>The string is prefixed {@code throttle} because this package is what publishes it, and
   * throttling is domain-agnostic: an endpoint outside {@code auth} answers with the same code.
   */
  LIMIT_EXCEEDED("throttle.limit-exceeded", 429);

  private final String code;
  private final int status;

  ThrottleErrorCode(String code, int status) {
    this.code = code;
    this.status = status;
  }

  @Override
  public String getCode() {
    return code;
  }

  @Override
  public int getStatus() {
    return status;
  }
}
