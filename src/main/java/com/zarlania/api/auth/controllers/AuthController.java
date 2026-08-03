package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.CsrfTokenResponse;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.LoginRequest;
import com.zarlania.api.auth.dtos.RegisterRequest;
import com.zarlania.api.auth.dtos.ResendRequest;
import com.zarlania.api.auth.dtos.TokenResponse;
import com.zarlania.api.auth.dtos.VerifyRequest;
import com.zarlania.api.auth.services.AuthTokenService;
import com.zarlania.api.auth.services.AuthTokenService.MintedSession;
import com.zarlania.api.auth.services.RegistrationService;
import com.zarlania.api.errors.ApiException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.http.ClientIpResolver;
import com.zarlania.api.throttle.RateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
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

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String INVALID_TOKEN_MESSAGE = "Invalid or expired verification token";
  private static final String NO_REFRESH_TOKEN_MESSAGE = "No refresh token";
  private static final String THROTTLED_MESSAGE = "Too many requests";

  // Bucket keys are "<endpoint>:<client-ip>" and "<endpoint>:acct:<identifier>". An IP can never
  // collide with the account form, since no address starts with "acct:".
  private static final String KEY_SEPARATOR = ":";
  private static final String ACCOUNT_KEY_INFIX = ":acct:";
  private static final int MAX_ACCOUNT_KEY_LENGTH = 100;

  private static final String REGISTER_ENDPOINT = "register";
  private static final String RESEND_ENDPOINT = "resend";
  private static final String LOGIN_ENDPOINT = "login";
  private static final String REFRESH_ENDPOINT = "refresh";
  private static final String CSRF_ENDPOINT = "csrf";

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
  private final RateLimiter rateLimiter;
  private final ThrottleProperties throttleProperties;
  private final Clock clock;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servlet) {
    requireCapacity(REGISTER_ENDPOINT, throttleProperties.registerLimit(), servlet);
    requireAccountCapacity(
        REGISTER_ENDPOINT, throttleProperties.registerAccountLimit(), request.email());
    registrationService.register(request.email(), request.username(), request.password());
  }

  // The client's way in to the CSRF pair guarding /auth/refresh and /auth/logout. Reading the
  // CsrfToken argument is what makes the token exist: Spring Security defers generation until
  // something asks for it, and that first read is also what writes the matching Set-Cookie onto
  // this response. The client keeps the returned value in memory and sends it back in headerName.
  //
  // There is an endpoint at all — rather than the usual "read the cookie from JavaScript" — because
  // the browser client is served from a different host than this API, and document.cookie is scoped
  // by host. It also has to be reachable on a cold start: the refresh cookie is HttpOnly and
  // outlives a page reload, but the token the client held in memory does not, so without this a
  // reloaded tab could never refresh its session again.
  //
  // Throttled per IP like every other public /auth route, though it is the cheapest of them —
  // no database work, no hashing, just a random token.
  @GetMapping("/csrf")
  public CsrfTokenResponse csrf(CsrfToken token, HttpServletRequest servlet) {
    requireCapacity(CSRF_ENDPOINT, throttleProperties.csrfLimit(), servlet);
    return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
  }

  @PostMapping("/verify")
  public void verify(@Valid @RequestBody VerifyRequest request) {
    if (!registrationService.verify(request.token())) {
      throw new ApiException(ErrorCode.INVALID_TOKEN, INVALID_TOKEN_MESSAGE);
    }
  }

  @PostMapping("/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resend(@Valid @RequestBody ResendRequest request, HttpServletRequest servlet) {
    requireCapacity(RESEND_ENDPOINT, throttleProperties.resendLimit(), servlet);
    requireAccountCapacity(
        RESEND_ENDPOINT, throttleProperties.resendAccountLimit(), request.email());
    registrationService.resend(request.email());
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
    requireCapacity(LOGIN_ENDPOINT, throttleProperties.loginLimit(), servlet);
    requireAccountCapacity(
        LOGIN_ENDPOINT, throttleProperties.loginAccountLimit(), request.identifier());
    MintedSession session = authTokenService.login(request.identifier(), request.password());
    return withSession(session);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
      HttpServletRequest servlet) {
    requireCapacity(REFRESH_ENDPOINT, throttleProperties.refreshLimit(), servlet);
    if (cookie == null) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, NO_REFRESH_TOKEN_MESSAGE);
    }
    return withSession(authTokenService.refresh(cookie));
  }

  // Key is <endpoint>:<client-ip>. The address comes from ClientIpResolver rather than from
  // getRemoteAddr() or X-Forwarded-For, because the deployed chain has two appending hops
  // (client -> Cloudflare -> Render's load balancer -> here) and none of those three sources gives
  // the caller: getRemoteAddr() and the header's rightmost entry are both Render's load balancer,
  // one address shared by the entire service, while the leftmost entry is written by the client and
  // can be rotated for a fresh bucket per request. The resolver reads CF-Connecting-IP, which
  // Cloudflare replaces rather than appends. See ClientIpResolver for the full derivation.
  private void requireCapacity(String endpoint, int limit, HttpServletRequest request) {
    consumeOrThrow(endpoint + KEY_SEPARATOR + ClientIpResolver.resolve(request), limit);
  }

  // A second, independent bucket keyed on the account a request names rather than on where it came
  // from. Per-IP alone leaves credential stuffing distributed across many addresses against one
  // known username limited only by how many addresses the attacker controls, which is why the spec
  // asked for per-IP *and* per-account limits.
  //
  // The identifier is trimmed and lower-cased because email and username are citext columns:
  // Postgres treats "Bob" and "bob" as the same account, so keying on the raw string would hand an
  // attacker a fresh bucket per spelling. Locale.ROOT rather than the default locale, so the
  // mapping cannot shift with the server's locale.
  //
  // The bucket exists for whatever string the caller supplied, whether or not an account matches
  // it, which is what keeps this out of the enumeration channel: a 429 says the identifier has been
  // tried a lot, never that it is real. Nor is this an account lockout, which the spec declined —
  // the window is a minute and refills itself; a sustained attack can suppress one account's logins
  // while it runs, but nothing stays locked once it stops.
  private void requireAccountCapacity(String endpoint, int limit, String accountIdentifier) {
    consumeOrThrow(endpoint + ACCOUNT_KEY_INFIX + accountKey(accountIdentifier), limit);
  }

  // Truncated because `identifier` is only @NotBlank — a caller could otherwise mint arbitrarily
  // long keys in the limiter's map. Sharing a bucket between two accounts whose first 100
  // characters match only ever makes the limit stricter, never weaker.
  private static String accountKey(String accountIdentifier) {
    String normalized = accountIdentifier.trim().toLowerCase(Locale.ROOT);
    return normalized.length() <= MAX_ACCOUNT_KEY_LENGTH
        ? normalized
        : normalized.substring(0, MAX_ACCOUNT_KEY_LENGTH);
  }

  private void consumeOrThrow(String key, int limit) {
    if (!rateLimiter.tryConsume(key, limit)) {
      throw new ApiException(ErrorCode.THROTTLED, THROTTLED_MESSAGE);
    }
  }

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

  // Built through the same helper as the live cookie above, rather than a separately assembled
  // ResponseCookie, so set and clear can never drift apart on HttpOnly/Secure/SameSite/Path —
  // only the value and Max-Age differ, and both differences are the point of clearing.
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
