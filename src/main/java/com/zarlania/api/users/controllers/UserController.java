package com.zarlania.api.users.controllers;

import com.zarlania.api.auth.AuthPrincipal;
import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.MeResponse;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the caller's own identity, resolved from the bearer token's {@link AuthPrincipal}. */
@RestController
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final OrganizationService organizationService;

  /**
   * Returns the caller's own user and active organization, resolved from the bearer token rather
   * than from anything in the request, so one caller can never read another's identity.
   */
  @GetMapping("/users/me")
  public MeResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
    // Unreachable for a live session: a token outliving its user or organization needs the row to
    // have been deleted inside the access token's 15-minute TTL, and the only deletion path
    // (UnverifiedAccountCleanup) applies to accounts that were never able to log in. If it ever
    // happens, orElseThrow's bare NoSuchElementException surfaces as a 500 — the same trade-off
    // AuthTokenService.login makes for the same impossible case, rather than inventing an ErrorCode
    // for a state that cannot occur.
    User user = userService.findById(principal.userId()).orElseThrow();
    Organization organization =
        organizationService.findById(principal.organizationId()).orElseThrow();
    return new MeResponse(user, organization);
  }
}
