package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.CsrfTokenResponse;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.LoginRequest;
import com.zarlania.api.auth.dtos.MintedSession;
import com.zarlania.api.auth.dtos.RegisterRequest;
import com.zarlania.api.auth.dtos.ResendRequest;
import com.zarlania.api.auth.dtos.TokenResponse;
import com.zarlania.api.auth.dtos.VerifyRequest;
import com.zarlania.api.auth.services.AuthTokenService;
import com.zarlania.api.auth.services.RegistrationService;
import com.zarlania.api.errors.ApiException;
import com.zarlania.api.throttle.Throttled;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public entry points to registration, email verification and session issuance.
 *
 * <p>Every route here is reachable without a token — nothing has one yet — so all but {@code
 * /verify} and {@code /logout} are rate limited. The limits are declared with {@link Throttled} and
 * applied by {@code ThrottleAspect}; none of the methods below contains throttling code, and the
 * limits themselves live in {@code zarlania.throttle.endpoints}.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String INVALID_TOKEN_MESSAGE = "Invalid or expired verification token";
  private static final String NO_REFRESH_TOKEN_MESSAGE = "No refresh token";

  private static final String REFRESH_COOKIE = "zarlania_refresh";
  private static final String REFRESH_COOKIE_PATH = "/auth";
  private static final String SAME_SITE_STRICT = "Strict";
  // logout clears the cookie by re-issuing it with the same attributes and this Max-Age instead
  // of a real one, which is what tells the browser to drop it immediately.
  private static final long CLEARED_COOKIE_MAX_AGE_SECONDS = 0;
  private static final String EMPTY_COOKIE_VALUE = "";

  private final RegistrationService registrationService;
  private final AuthTokenService authTokenService;
  private final AuthProperties authProperties;
  private final Clock clock;

  /**
   * Starts registration: creates the account and sends a verification email.
   *
   * <p>Answers 202 rather than 201 because the account is not usable yet — it cannot log in until
   * the emailed token is presented to {@link #verify}.
   */
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Throttled(endpoint = "register", accountFrom = "email")
  public void register(@Valid @RequestBody RegisterRequest request) {
    registrationService.register(request.email(), request.username(), request.password());
  }

  /**
   * Hands the client the CSRF token pair guarding {@link #refresh} and {@link #logout}.
   *
   * <p>Reading the {@link CsrfToken} argument is what makes the token exist: Spring Security defers
   * generation until something asks for it, and that first read is also what writes the matching
   * {@code Set-Cookie} onto this response. The client keeps the returned value in memory and sends
   * it back in {@code headerName}.
   *
   * <p>There is an endpoint at all — rather than the usual "read the cookie from JavaScript" —
   * because the browser client is served from a different host than this API, and {@code
   * document.cookie} is scoped by host. It also has to be reachable on a cold start: the refresh
   * cookie is HttpOnly and outlives a page reload, but the token the client held in memory does
   * not, so without this a reloaded tab could never refresh its session again.
   *
   * <p>Throttled per IP like every other public route here, though it is the cheapest of them — no
   * database work, no hashing, just a random token.
   */
  @GetMapping("/csrf")
  @Throttled(endpoint = "csrf")
  public CsrfTokenResponse csrf(CsrfToken token) {
    return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
  }

  /**
   * Completes registration by consuming an emailed verification token.
   *
   * @throws ApiException with {@link AuthErrorCode#INVALID_TOKEN} if the token is unknown, expired
   *     or already consumed — the three are indistinguishable to the caller on purpose
   */
  @PostMapping("/verify")
  public void verify(@Valid @RequestBody VerifyRequest request) {
    if (!registrationService.verify(request.token())) {
      throw new ApiException(AuthErrorCode.INVALID_TOKEN, INVALID_TOKEN_MESSAGE);
    }
  }

  /**
   * Sends a fresh verification email for an address that has not been verified yet.
   *
   * <p>Answers 202 for every input, including addresses that do not exist and addresses already
   * verified, so the response cannot be used to enumerate accounts.
   */
  @PostMapping("/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Throttled(endpoint = "resend", accountFrom = "email")
  public void resend(@Valid @RequestBody ResendRequest request) {
    registrationService.resend(request.email());
  }

  /**
   * Exchanges an email-or-username and password for a session: an access token in the body and a
   * refresh token in an HttpOnly cookie.
   *
   * <p>Answers {@link AuthErrorCode#INVALID_CREDENTIALS} for a wrong password and for an unknown
   * identifier alike, and {@link AuthErrorCode#EMAIL_UNVERIFIED} when the password was right but
   * the address was never verified. Both come from the exceptions {@code AuthTokenService} throws,
   * by way of {@link AuthExceptionHandler} — this method raises neither itself.
   */
  @PostMapping("/login")
  @Throttled(endpoint = "login", accountFrom = "identifier")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return withSession(authTokenService.login(request.identifier(), request.password()));
  }

  /**
   * Rotates the refresh cookie and mints a new access token.
   *
   * <p>Authenticates with the {@code zarlania_refresh} cookie rather than a bearer token, which is
   * why it is one of the two routes {@code SecurityConfig} guards with a CSRF token.
   *
   * @throws ApiException with {@link AuthErrorCode#INVALID_CREDENTIALS} if no cookie was sent at
   *     all — the one failure this method decides for itself, since a missing cookie is an HTTP
   *     fact rather than anything the service could be asked about. A cookie that is expired,
   *     revoked or already redeemed is refused by {@code AuthTokenService} and answered with the
   *     same code by {@link AuthExceptionHandler}, so the client cannot tell the cases apart.
   */
  @PostMapping("/refresh")
  @Throttled(endpoint = "refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
    if (cookie == null) {
      throw new ApiException(AuthErrorCode.INVALID_CREDENTIALS, NO_REFRESH_TOKEN_MESSAGE);
    }
    return withSession(authTokenService.refresh(cookie));
  }

  /**
   * Revokes the whole refresh-token family and clears the cookie.
   *
   * <p>Answers 204 whether or not a cookie was sent: a client asking to be logged out is told it is
   * logged out, and a missing or unknown cookie means it already was.
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie) {
    if (cookie != null) {
      authTokenService.logout(cookie);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
        .build();
  }

  private ResponseEntity<TokenResponse> withSession(MintedSession session) {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refresh()).toString())
        .body(new TokenResponse(session.accessToken()));
  }

  private ResponseCookie refreshCookie(IssuedRefreshToken refresh) {
    long maxAgeSeconds = Duration.between(clock.instant(), refresh.familyExpiresAt()).toSeconds();
    return buildRefreshCookie(refresh.raw(), maxAgeSeconds);
  }

  /**
   * Built through the same helper as the live cookie, rather than a separately assembled {@link
   * ResponseCookie}, so set and clear can never drift apart on HttpOnly/Secure/SameSite/Path — only
   * the value and Max-Age differ, and both differences are the point of clearing.
   */
  private ResponseCookie clearedRefreshCookie() {
    return buildRefreshCookie(EMPTY_COOKIE_VALUE, CLEARED_COOKIE_MAX_AGE_SECONDS);
  }

  private ResponseCookie buildRefreshCookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(authProperties.cookieSecure())
        .sameSite(SAME_SITE_STRICT)
        .path(REFRESH_COOKIE_PATH)
        .maxAge(maxAgeSeconds)
        .build();
  }
}
