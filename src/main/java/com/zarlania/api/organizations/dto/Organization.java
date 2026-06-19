package com.zarlania.api.organizations.dto;

import com.zarlania.api.organizations.OrganizationType;
import java.util.UUID;

/**
 * Immutable view of an organization for use across the domain boundary and in API responses. This
 * DTO — not the JPA {@code OrganizationEntity} — is the type passed throughout the application.
 *
 * @param id the organization's id
 * @param name the organization's display name
 * @param type whether the organization is {@code PERSONAL} or {@code GENERAL}
 */
public record Organization(UUID id, String name, OrganizationType type) {}
