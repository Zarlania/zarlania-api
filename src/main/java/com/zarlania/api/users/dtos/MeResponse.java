package com.zarlania.api.users.dtos;

import com.zarlania.api.organizations.dtos.OrganizationDto;

/** The response body for {@code GET /users/me}: the caller's identity and active organization. */
public record MeResponse(UserDto user, OrganizationDto organization) {}
