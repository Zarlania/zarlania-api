package com.zarlania.api.errors;

/**
 * The error code for a request body that failed bean validation.
 *
 * <p>Lives here rather than in a domain because no domain raises it: {@link GlobalExceptionHandler}
 * answers {@code MethodArgumentNotValidException} for every endpoint in the application, and the
 * failure is about the shape of the request rather than about anything a domain rule decided.
 */
public enum ValidationErrorCode implements ErrorCode {
  /** One or more fields failed their constraints; the response lists which. */
  FAILED("validation.failed", 400);

  private final String code;
  private final int status;

  ValidationErrorCode(String code, int status) {
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
