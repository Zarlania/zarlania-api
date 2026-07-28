package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.LoginRequest;
import com.zarlania.api.auth.dtos.RegisterRequest;
import com.zarlania.api.auth.dtos.ResendRequest;
import com.zarlania.api.auth.dtos.TokenResponse;
import com.zarlania.api.auth.dtos.VerifyRequest;
import com.zarlania.api.auth.services.AuthTokenService;
import com.zarlania.api.auth.services.AuthTokenService.MintedSession;
import com.zarlania.api.auth.services.RegistrationService;
import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.common.http.ClientIpResolver;
import com.zarlania.api.common.throttle.RateLimiter;
import com.zarlania.api.common.throttle.ThrottleProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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

  private static final String REGISTER_ENDPOINT = "register";
  private static final String RESEND_ENDPOINT = "resend";
  private static final String LOGIN_ENDPOINT = "login";
  private static final String REFRESH_ENDPOINT = "refresh";

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
    registrationService.register(request.email(), request.username(), request.password());
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
    registrationService.resend(request.email());
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
    requireCapacity(LOGIN_ENDPOINT, throttleProperties.loginLimit(), servlet);
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

  // Key is <endpoint>:<client-ip>. The address comes from ClientIpResolver, not getRemoteAddr():
  // behind Render's proxy the latter is the proxy's own address, and the obvious fix — letting
  // Spring rewrite it from X-Forwarded-For — reads the *leftmost* entry, which the client wrote
  // and can rotate at will for a fresh bucket per request. The resolver reads the rightmost
  // entry, the one the proxy appended. See ClientIpResolver for the full derivation.
  private void requireCapacity(String endpoint, int limit, HttpServletRequest request) {
    String key = endpoint + ":" + ClientIpResolver.resolve(request);
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
