package com.zarlania.api.users.dtos;

import com.zarlania.api.organizations.dtos.Organization;

/** The response body for {@code GET /users/me}: the caller's identity and active organization. */
public record MeResponse(User user, Organization organization) {}
