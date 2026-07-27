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
    // orElseThrow surfaces as a 500 today if a valid token outlives its user or
    // organization; Task 11 owns the ProblemDetail contract that will replace this.
    UserDto user = userService.findById(principal.userId()).orElseThrow();
    OrganizationDto organization =
        organizationService.findById(principal.organizationId()).orElseThrow();
    return new MeResponse(user, organization);
  }
}
