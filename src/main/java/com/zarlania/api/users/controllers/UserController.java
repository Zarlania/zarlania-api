package com.zarlania.api.users.controllers;

import com.zarlania.api.auth.AuthPrincipal;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.MeResponse;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the caller's own identity, resolved from the bearer token's {@link AuthPrincipal}. */
@RestController
@RequiredArgsConstructor
public class UserController {

  private static final String ME_PATH = "/users/me";

  private final UserService userService;
  private final OrganizationService organizationService;

  @GetMapping(ME_PATH)
  public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
    // Unreachable for a live session: a token outliving its user or organization needs the row to
    // have been deleted inside the access token's 15-minute TTL, and the only deletion path
    // (UnverifiedAccountCleanup) applies to accounts that were never able to log in. If it ever
    // happens, orElseThrow's bare NoSuchElementException surfaces as a 500 — the same trade-off
    // AuthTokenService.login makes for the same impossible case, rather than inventing an ErrorCode
    // for a state that cannot occur.
    UserDto user = userService.findById(principal.userId()).orElseThrow();
    OrganizationDto organization =
        organizationService.findById(principal.organizationId()).orElseThrow();
    return new MeResponse(user, organization);
  }
}
