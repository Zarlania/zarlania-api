package com.zarlania.api.users;

import java.util.UUID;

/**
 * Immutable view of a {@link User} for use across the domain boundary and in API responses.
 *
 * @param id the user's id
 * @param email the user's email
 */
public record UserDto(UUID id, String email) {

  /**
   * Maps an entity to its DTO.
   *
   * @param user the source entity
   * @return a DTO carrying the entity's id and email
   */
  public static UserDto from(User user) {
    return new UserDto(user.getId(), user.getEmail());
  }
}
