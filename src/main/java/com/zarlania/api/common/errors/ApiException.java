package com.zarlania.api.common.errors;

/**
 * A domain rule violation that should surface to the client as a specific {@link ErrorCode} and
 * HTTP status, rather than a generic 500. {@link GlobalExceptionHandler} translates every instance
 * into a {@code ProblemDetail}.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public ApiException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
