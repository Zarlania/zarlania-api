package com.zarlania.api.errors;

/**
 * Stable machine-readable codes for {@link ApiException}, exposed to clients as the {@code code}
 * property on the RFC 9457 {@code ProblemDetail} body. The code string is the contract — Tasks 12
 * and 15 assert on it, so it must never change once shipped.
 */
public enum ErrorCode {
  USERNAME_TAKEN("auth.username-taken", 409),
  EMAIL_UNVERIFIED("auth.email-unverified", 403),
  INVALID_CREDENTIALS("auth.invalid-credentials", 401),
  INVALID_TOKEN("auth.invalid-token", 400),
  THROTTLED("auth.throttled", 429),
  VALIDATION_FAILED("validation.failed", 400);

  private final String code;
  private final int status;

  ErrorCode(String code, int status) {
    this.code = code;
    this.status = status;
  }

  public String getCode() {
    return code;
  }

  public int getStatus() {
    return status;
  }
}
