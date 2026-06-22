package com.zarlania.api.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions across all domains to RFC 7807 {@link ProblemDetail} responses. The codebase's
 * first global web exception handler; it lives in the shared {@code web} package because it serves
 * every domain's controllers. Importing another domain's exception types is permitted under
 * ADR-0011 (that rule forbids importing entities, not exceptions).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so that overrides take priority over Spring
 * Boot's auto-configured {@code ProblemDetailsExceptionHandler} for the same exception types.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  /** Bean-validation failures at the HTTP edge: 400 with a per-field {@code errors} map. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setDetail("Request validation failed");
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    problem.setProperty("errors", errors);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }
}
