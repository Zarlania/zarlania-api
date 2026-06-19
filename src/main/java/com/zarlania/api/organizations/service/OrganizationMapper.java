package com.zarlania.api.organizations.service;

import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import org.springframework.stereotype.Component;

/** Maps {@code organizations} entities to their DTOs for crossing the domain boundary. */
@Component
public class OrganizationMapper {

  /**
   * Maps an organization entity to its DTO.
   *
   * @param entity the source entity
   * @return a DTO carrying the entity's id, name, and type
   */
  public Organization toDto(OrganizationEntity entity) {
    return new Organization(entity.getId(), entity.getName(), entity.getType());
  }

  /**
   * Maps a membership entity to its DTO, exposing only the organization's id (never the entity).
   *
   * @param entity the source entity
   * @return a DTO carrying the organization id, user id, and role
   */
  public Membership toDto(MembershipEntity entity) {
    return new Membership(entity.getOrganization().getId(), entity.getUserId(), entity.getRole());
  }
}
