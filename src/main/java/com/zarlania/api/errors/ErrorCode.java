package com.zarlania.api.errors;

/**
 * Stable machine-readable codes for every error this API answers with, exposed to clients as the
 * {@code code} property on the RFC 9457 {@code ProblemDetail} body.
 *
 * <p>The code string is the contract clients branch on, and {@code zarlania-app} matches these
 * exact strings — a shipped one must never change. The status beside it is what makes an error
 * answerable without the thrower knowing any HTTP: a domain throws its own exception, and its
 * {@code @RestControllerAdvice} picks the code from here.
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

  /** The stable string clients branch on. Part of the API contract — never change a shipped one. */
  public String getCode() {
    return code;
  }

  /** The HTTP status this code answers with. */
  public int getStatus() {
    return status;
  }
}
