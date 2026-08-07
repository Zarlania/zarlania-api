package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.exceptions.EmailUnverifiedException;
import com.zarlania.api.auth.exceptions.InvalidCredentialsException;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.errors.ProblemDetails;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the auth domain's exceptions into HTTP answers. The services throw exceptions that name
 * what went wrong and nothing about how it is reported, which is what lets them be read — and
 * reused — without reference to a transport; this class decides what a client is told.
 *
 * <p>It is not the only place in the domain that answers with a status. {@link AuthController}
 * raises {@link com.zarlania.api.errors.ApiException} directly for the two failures it decides for
 * itself — an unredeemable verification token, a missing refresh cookie — which is legitimate,
 * because a controller is already the HTTP layer and neither failure is something a service could
 * be asked about. Those take the same route to the wire: {@code GlobalExceptionHandler} renders an
 * {@code ApiException} through {@link com.zarlania.api.errors.ProblemDetails}, exactly as the
 * methods below do, so status, {@code code} and body shape are identical whichever path a failure
 * takes. What must stay true is that both draw their codes from {@link AuthErrorCode}; a status
 * invented at either site is the thing that would break the contract.
 *
 * <p>Scoped to this package rather than registered globally, so the mapping travels with the domain
 * if it is ever lifted out of the monolith, and so no other domain inherits auth's answers by
 * accident.
 *
 * <p>The messages live here, not on the exceptions. Several of them are deliberately identical
 * across different causes, and keeping them side by side is what makes that property checkable by
 * reading one file.
 *
 * <p>No handler below declares the exception as a parameter. The {@code @ExceptionHandler}
 * annotation already names every type it maps, so a parameter would be a second, silently unchecked
 * statement of the same thing — and none of these answers depends on the instance, since each is a
 * fixed status, code and message. The exception's own detail is not lost: every one of these is
 * logged where it is thrown, with the ids that make it actionable, which is the only place that
 * information still exists.
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
  public ResponseEntity<ProblemDetail> handleInvalidCredentials() {
    return ProblemDetails.of(AuthErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
  }

  /** The password was right but the address was never proved: 403, saying so. */
  @ExceptionHandler(EmailUnverifiedException.class)
  public ResponseEntity<ProblemDetail> handleEmailUnverified() {
    return ProblemDetails.of(AuthErrorCode.EMAIL_UNVERIFIED, EMAIL_UNVERIFIED_MESSAGE);
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
  public ResponseEntity<ProblemDetail> handleRefreshRejected() {
    return ProblemDetails.of(AuthErrorCode.INVALID_CREDENTIALS, REFRESH_REJECTED_MESSAGE);
  }

  /** The one registration failure a caller is told about: 409. */
  @ExceptionHandler(UsernameTakenException.class)
  public ResponseEntity<ProblemDetail> handleUsernameTaken() {
    return ProblemDetails.of(AuthErrorCode.USERNAME_TAKEN, USERNAME_TAKEN_MESSAGE);
  }
}
