package com.zarlania.api.organizations.dtos;

import java.util.UUID;

/**
 * How an organization crosses out of its domain. Carries no membership list and no entity, so a
 * caller elsewhere can read an organization without being able to reach or mutate one.
 */
public record Organization(UUID id, String name, OrganizationType type) {}
