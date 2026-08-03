package com.zarlania.api.errors;

import java.util.Map;

/**
 * A domain rule violation that should surface to the client as a specific {@link ErrorCode} and
 * HTTP status, rather than a generic 500. {@link GlobalExceptionHandler} translates every instance
 * into a {@code ProblemDetail}.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Map<String, String> responseHeaders;

  /**
   * An error whose status and code are the whole of what the client needs.
   *
   * @param errorCode the machine-readable code and status to answer with
   * @param message the human-readable detail, returned to the client
   */
  public ApiException(ErrorCode errorCode, String message) {
    this(errorCode, message, Map.of());
  }

  /**
   * An error that also needs headers on the response — a 429's {@code Retry-After}, for instance,
   * which is part of the answer rather than decoration on it.
   *
   * @param errorCode the machine-readable code and status to answer with
   * @param message the human-readable detail, returned to the client
   * @param responseHeaders headers to set on the error response; copied, so a later change to the
   *     caller's map cannot alter what is sent
   */
  public ApiException(ErrorCode errorCode, String message, Map<String, String> responseHeaders) {
    super(message);
    this.errorCode = errorCode;
    this.responseHeaders = Map.copyOf(responseHeaders);
  }

  /** The code and status this error answers with. */
  public ErrorCode getErrorCode() {
    return errorCode;
  }

  /** Headers to set on the error response; empty for most errors. */
  public Map<String, String> getResponseHeaders() {
    return responseHeaders;
  }
}
