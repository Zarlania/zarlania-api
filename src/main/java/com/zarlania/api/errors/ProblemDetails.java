package com.zarlania.api.errors;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Builds the one RFC 9457 error response shape this API answers with, so that a domain's own
 * exception handler and {@link GlobalExceptionHandler} cannot drift into producing two.
 *
 * <p>Rendering is all this package owns. Which exception becomes which code, and what the code's
 * string and status are, both stay in the domain that raises it — this method takes any {@link
 * ErrorCode} implementation and never needs to know them.
 */
public final class ProblemDetails {

  private static final String CODE_PROPERTY = "code";

  private ProblemDetails() {}

  /**
   * A problem response carrying the code's status, the code itself, and a detail message.
   *
   * @param detail the human-readable explanation returned to the client; must not describe this
   *     service's internals, since whoever provoked the error reads it
   */
  public static ResponseEntity<ProblemDetail> of(ErrorCode errorCode, String detail) {
    return of(errorCode, detail, Map.of());
  }

  /**
   * A problem response that also sets headers — a 429's {@code Retry-After}, for instance, which is
   * part of the answer rather than decoration on it.
   *
   * <p>Returns a {@link ResponseEntity} rather than a bare {@link ProblemDetail} for exactly that
   * reason: a bare body has nowhere to put them.
   *
   * @param responseHeaders headers to set on the response; may be empty
   */
  public static ResponseEntity<ProblemDetail> of(
      ErrorCode errorCode, String detail, Map<String, String> responseHeaders) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(errorCode.getStatus());
    responseHeaders.forEach(response::header);
    return response.body(body(errorCode, detail));
  }

  /**
   * The problem body alone, for the one caller that cannot use {@link #of} — {@link
   * GlobalExceptionHandler}'s bean-validation hook, which must hand the body to its superclass
   * rather than build the response itself.
   */
  public static ProblemDetail body(ErrorCode errorCode, String detail) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(errorCode.getStatus()), detail);
    problem.setProperty(CODE_PROPERTY, errorCode.getCode());
    return problem;
  }
}
