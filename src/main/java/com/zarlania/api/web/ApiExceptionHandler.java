package com.zarlania.api.web;

import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import java.util.LinkedHashMap;
import java.util.Map;
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

  /**
   * Maps service-layer input rejections (thrown before bean validation runs) to 400. Intentionally
   * broad: any {@link IllegalArgumentException} not claimed by a more specific handler — including
   * infrastructure-thrown ones — lands here rather than escalating to 500.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  /** Email already registered: 409. Detail is fixed so the attempted value is not echoed back. */
  @ExceptionHandler(EmailAlreadyExistsException.class)
  ProblemDetail handleEmailConflict(EmailAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "An account with this email already exists");
  }

  /** Username already taken: 409. */
  @ExceptionHandler(UsernameAlreadyExistsException.class)
  ProblemDetail handleUsernameConflict(UsernameAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This username is already taken");
  }

  /**
   * The chosen username collides with an existing organization's globally-unique name, so the
   * personal org cannot be created: surfaced to the caller as an unavailable username (409).
   */
  @ExceptionHandler(OrganizationNameAlreadyExistsException.class)
  ProblemDetail handleUsernameUnavailable(OrganizationNameAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "The requested username is unavailable");
  }

  /** Defensive: cannot occur for a brand-new user, but mapped to 409 rather than 500. */
  @ExceptionHandler(PersonalOrganizationAlreadyExistsException.class)
  ProblemDetail handlePersonalOrgConflict(PersonalOrganizationAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "The user already owns a personal organization");
  }
}
