package com.zarlania.api.errors;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates every exception reaching a controller into an RFC 9457 {@link ProblemDetail}, so
 * clients get one consistent error shape instead of the framework's default HTML or ad hoc JSON. A
 * stable {@code code} property lets clients branch on the failure without parsing prose.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than catching {@code Exception}
 * outright: that base class already knows the correct status for the large family of framework
 * exceptions it maps — malformed JSON ({@code HttpMessageNotReadableException}, 400), a disallowed
 * HTTP verb ({@code HttpRequestMethodNotSupportedException}, 405), and the rest of that family.
 * Catching {@code Exception} unconditionally would have swallowed all of them into a misleading
 * 500. The catch-all below is reached only by what neither this class nor its superclass maps
 * explicitly: a genuine bug, an unreachable dependency, or similar.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final String ERRORS_PROPERTY = "errors";
  private static final String UNEXPECTED_ERROR_DETAIL = "Unexpected error";
  private static final String VALIDATION_FAILED_DETAIL = "One or more fields failed validation";

  /**
   * Answers a domain rule violation that already knows its own status and code.
   *
   * <p>This is the fallback for HTTP-aware infrastructure that raises an error directly — the
   * throttle aspect, a controller rejecting a missing cookie. A domain's <em>services</em> throw
   * their own exceptions instead, which that domain's own {@code @RestControllerAdvice} maps; see
   * {@code AuthExceptionHandler}.
   */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApiException(ApiException exception) {
    return ProblemDetails.of(
        exception.getErrorCode(), exception.getMessage(), exception.getResponseHeaders());
  }

  /**
   * Catches everything the framework family above and {@link ApiException} do not: a bug, an
   * unreachable dependency, a constraint violation that slipped past a pre-check.
   *
   * <p>The client gets a generic message either way — the real detail belongs in the log, not in a
   * response where it would describe this service's internals to whoever provoked it.
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpectedException(Exception exception) {
    log.error("Unhandled exception reached GlobalExceptionHandler", exception);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_DETAIL);
  }

  /**
   * Answers a request whose body failed bean validation, listing which fields failed and why.
   *
   * <p>Overriding this protected hook — rather than adding a second {@code @ExceptionHandler} for
   * {@link MethodArgumentNotValidException} — is what lets the superclass keep routing the rest of
   * its exception family through its own default handling. A second handler for this exact type
   * would collide with the superclass's mapping for it and fail to start.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        ProblemDetails.body(ErrorCode.VALIDATION_FAILED, VALIDATION_FAILED_DETAIL);
    problem.setProperty(ERRORS_PROPERTY, fieldErrors(exception));
    return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
  }

  private static Map<String, String> fieldErrors(MethodArgumentNotValidException exception) {
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return errors;
  }
}
