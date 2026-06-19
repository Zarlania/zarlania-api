package com.zarlania.api.users.service;

import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.entity.UserEntity;
import org.springframework.stereotype.Component;

/** Maps {@link UserEntity} rows to the {@link User} DTO for crossing the domain boundary. */
@Component
public class UserMapper {

  /**
   * Maps an entity to its DTO.
   *
   * @param entity the source entity
   * @return a DTO carrying the entity's id, email, and display name
   */
  public User toDto(UserEntity entity) {
    return new User(entity.getId(), entity.getEmail(), entity.getDisplayName());
  }
}
