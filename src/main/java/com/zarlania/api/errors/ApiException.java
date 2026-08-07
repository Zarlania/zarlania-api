package com.zarlania.api.errors;

import java.util.Map;

/**
 * A failure that already knows the {@link ErrorCode} it should surface to the client as, rather
 * than a generic 500. {@link GlobalExceptionHandler} renders every instance through {@link
 * ProblemDetails}.
 *
 * <p>For code that is <em>already</em> an HTTP concern and has nothing to defer to a handler: a
 * controller rejecting a malformed request of its own accord, or infrastructure in the request path
 * such as {@code ThrottleAspect}. A service must not throw this — it would put a status code in the
 * business layer and tie the domain to this package. Services throw their own exceptions, which the
 * domain's own {@code @RestControllerAdvice} maps to a code from that domain's {@link ErrorCode}
 * enum.
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
