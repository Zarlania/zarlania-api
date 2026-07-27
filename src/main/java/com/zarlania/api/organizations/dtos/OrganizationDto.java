package com.zarlania.api.organizations.dtos;

import com.zarlania.api.organizations.entities.OrganizationType;
import java.util.UUID;

public record OrganizationDto(UUID id, String name, OrganizationType type) {}
