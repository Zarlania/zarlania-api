package com.zarlania.api.organizations.dtos;

import com.zarlania.api.organizations.entities.OrganizationType;
import java.util.UUID;

/**
 * How an organization crosses out of its domain. Carries no membership list and no entity, so a
 * caller elsewhere can read an organization without being able to reach or mutate one.
 */
public record OrganizationDto(UUID id, String name, OrganizationType type) {}
