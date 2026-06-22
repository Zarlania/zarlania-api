package com.zarlania.api.identity.dto;

import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.users.dto.User;

/**
 * The result of creating an account: the new user together with their personal organization.
 * Carries the canonical domain name and composes the {@code users} and {@code organizations}
 * domains' DTOs; no entities cross the boundary (ADR-0011).
 *
 * @param user the created user
 * @param personalOrganization the user's personal organization, named after the username
 */
public record Account(User user, Organization personalOrganization) {}
