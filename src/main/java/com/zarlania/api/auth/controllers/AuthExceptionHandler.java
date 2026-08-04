package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.exceptions.EmailUnverifiedException;
import com.zarlania.api.auth.exceptions.InvalidCredentialsException;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.errors.ProblemDetails;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the auth domain's exceptions into HTTP answers. This is the only place in the domain that
 * knows a status code or a client-facing message: the services throw exceptions that name what went
 * wrong and nothing about how it is reported, which is what lets them be read — and reused —
 * without reference to a transport.
 *
 * <p>Scoped to this package rather than registered globally, so the mapping travels with the domain
 * if it is ever lifted out of the monolith, and so no other domain inherits auth's answers by
 * accident.
 *
 * <p>The messages live here, not on the exceptions. Several of them are deliberately identical
 * across different causes, and keeping them side by side is what makes that property checkable by
 * reading one file.
 */
@RestControllerAdvice(basePackageClasses = AuthExceptionHandler.class)
public class AuthExceptionHandler {

  // Deliberately identical for both branches of a failed login (unknown identifier, known
  // identifier with a wrong password) — same status, same code, same detail — so a client
  // response can never reveal which one occurred.
  private static final String INVALID_CREDENTIALS_MESSAGE = "Bad credentials";
  private static final String EMAIL_UNVERIFIED_MESSAGE = "Verify your email first";
  private static final String REFRESH_REJECTED_MESSAGE = "Refresh token rejected";
  private static final String USERNAME_TAKEN_MESSAGE = "That username is taken";

  /** A wrong password and an unknown identifier alike: 401, with no hint which it was. */
  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCredentials(
      InvalidCredentialsException exception) {
    return ProblemDetails.of(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
  }

  /** The password was right but the address was never proved: 403, saying so. */
  @ExceptionHandler(EmailUnverifiedException.class)
  public ResponseEntity<ProblemDetail> handleEmailUnverified(EmailUnverifiedException exception) {
    return ProblemDetails.of(ErrorCode.EMAIL_UNVERIFIED, EMAIL_UNVERIFIED_MESSAGE);
  }

  /**
   * Every way a refresh token can be refused, answered identically.
   *
   * <p>A replayed token ({@link ReusedRefreshTokenException}) is treated as evidence of theft and
   * has already cost the whole family its validity by the time this runs — but the client is told
   * only that the token was rejected, exactly as for one that was merely expired. Answering the two
   * differently would tell an attacker holding a stolen token that they had been detected.
   */
  @ExceptionHandler({InvalidRefreshTokenException.class, ReusedRefreshTokenException.class})
  public ResponseEntity<ProblemDetail> handleRefreshRejected(RuntimeException exception) {
    return ProblemDetails.of(ErrorCode.INVALID_CREDENTIALS, REFRESH_REJECTED_MESSAGE);
  }

  /** The one registration failure a caller is told about: 409. */
  @ExceptionHandler(UsernameTakenException.class)
  public ResponseEntity<ProblemDetail> handleUsernameTaken(UsernameTakenException exception) {
    return ProblemDetails.of(ErrorCode.USERNAME_TAKEN, USERNAME_TAKEN_MESSAGE);
  }
}
