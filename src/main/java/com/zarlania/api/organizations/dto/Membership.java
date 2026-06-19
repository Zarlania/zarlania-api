package com.zarlania.api.organizations.dto;

import com.zarlania.api.organizations.MembershipRole;
import java.util.UUID;

/**
 * Immutable view of a user's membership in an organization, referencing both by opaque id. This DTO
 * — not the JPA {@code MembershipEntity} — is the type passed throughout the application.
 *
 * @param organizationId the organization the membership belongs to
 * @param userId the member's user id
 * @param role the member's role in the organization
 */
public record Membership(UUID organizationId, UUID userId, MembershipRole role) {}
