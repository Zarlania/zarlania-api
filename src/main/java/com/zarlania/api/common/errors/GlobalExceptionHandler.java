package com.zarlania.api.common.errors;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates every exception reaching a controller into an RFC 9457 {@link ProblemDetail}, so
 * clients get one consistent error shape instead of the framework's default HTML or ad hoc JSON. A
 * stable {@code code} property lets clients branch on the failure without parsing prose.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String CODE_PROPERTY = "code";
  private static final String ERRORS_PROPERTY = "errors";
  private static final String UNEXPECTED_ERROR_DETAIL = "Unexpected error";

  @ExceptionHandler(ApiException.class)
  public ProblemDetail handleApiException(ApiException exception) {
    ErrorCode errorCode = exception.getErrorCode();
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(errorCode.getStatus()), exception.getMessage());
    problem.setProperty(CODE_PROPERTY, errorCode.getCode());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationException(MethodArgumentNotValidException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED.getCode());
    problem.setProperty(CODE_PROPERTY, ErrorCode.VALIDATION_FAILED.getCode());
    problem.setProperty(ERRORS_PROPERTY, fieldErrors(exception));
    return problem;
  }

  // Catches everything ApiException and MethodArgumentNotValidException do not: a bug, an
  // unreachable dependency, a constraint violation that slipped past a pre-check. The client
  // gets a generic message either way — the real detail belongs in the log, not the response.
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpectedException(Exception exception) {
    log.error("Unhandled exception reached GlobalExceptionHandler", exception);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_DETAIL);
  }

  private static Map<String, String> fieldErrors(MethodArgumentNotValidException exception) {
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return errors;
  }
}
