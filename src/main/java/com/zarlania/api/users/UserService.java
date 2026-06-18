package com.zarlania.api.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and looks up users. The public surface of the {@code users} domain. */
@Service
public class UserService {

  private final UserRepository users;

  UserService(UserRepository users) {
    this.users = users;
  }

  /**
   * Creates a user with the given email.
   *
   * @param email a non-blank email, unique across users
   * @return the created user as a DTO
   * @throws IllegalArgumentException if {@code email} is null or blank
   * @throws EmailAlreadyExistsException if a user with that email already exists
   */
  @Transactional
  public UserDto create(String email) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (users.existsByEmail(email)) {
      throw new EmailAlreadyExistsException(email);
    }
    User user = new User();
    user.setEmail(email);
    return UserDto.from(users.save(user));
  }

  /**
   * Finds a user by id.
   *
   * @param id the user id
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<UserDto> findById(UUID id) {
    return users.findById(id).map(UserDto::from);
  }

  /**
   * Finds a user by exact email.
   *
   * @param email the email to match
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<UserDto> findByEmail(String email) {
    return users.findByEmail(email).map(UserDto::from);
  }
}
