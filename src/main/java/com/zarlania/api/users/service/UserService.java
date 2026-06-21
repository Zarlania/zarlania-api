package com.zarlania.api.users.service;

import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.entity.UserEntity;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import com.zarlania.api.users.repository.UserRepository;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and looks up users. The public surface of the {@code users} domain. */
@Service
@RequiredArgsConstructor
public class UserService {

  /** Name of the email unique constraint in {@code V1__create_users_table.sql}. */
  private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

  /** Name of the username unique constraint in {@code V3__...sql}. */
  private static final String USERNAME_UNIQUE_CONSTRAINT = "uq_users_username";

  private final UserRepository userRepository;
  private final UserMapper userMapper;

  /**
   * Creates a user with the given email and username.
   *
   * @param email a non-blank email, unique across users
   * @param username a non-blank unique public handle
   * @return the created user as a DTO
   * @throws IllegalArgumentException if {@code email} or {@code username} is null or blank
   * @throws EmailAlreadyExistsException if a user with that email already exists
   * @throws UsernameAlreadyExistsException if a user with that username already exists
   */
  @Transactional
  public User create(String email, String username) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username must not be blank");
    }
    if (userRepository.existsByEmail(email)) {
      throw EmailAlreadyExistsException.forEmail(email);
    }
    if (userRepository.existsByUsername(username)) {
      throw UsernameAlreadyExistsException.forUsername(username);
    }
    UserEntity entity = new UserEntity();
    entity.setEmail(email);
    entity.setUsername(username);
    try {
      // saveAndFlush forces the INSERT now so a concurrent duplicate that slipped past a pre-check
      // surfaces here as the relevant unique-constraint violation. Only those specific violations
      // map to a domain exception; any other integrity failure is rethrown unchanged.
      return userMapper.toDto(userRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException ex) {
      if (isConstraintViolation(ex, EMAIL_UNIQUE_CONSTRAINT)) {
        throw EmailAlreadyExistsException.forEmail(email);
      }
      if (isConstraintViolation(ex, USERNAME_UNIQUE_CONSTRAINT)) {
        throw UsernameAlreadyExistsException.forUsername(username);
      }
      throw ex;
    }
  }

  /**
   * Reports whether the violation's cause chain names the given constraint. Matching the constraint
   * name (which appears in both H2 and PostgreSQL messages) avoids catching unrelated integrity
   * failures and avoids depending on a JPA-provider-specific typed exception.
   */
  private static boolean isConstraintViolation(
      DataIntegrityViolationException ex, String constraintName) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains(constraintName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds a user by id.
   *
   * @param id the user id
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<User> findById(UUID id) {
    return userRepository.findById(id).map(userMapper::toDto);
  }

  /**
   * Finds a user by exact email.
   *
   * @param email the email to match
   * @return the user as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<User> findByEmail(String email) {
    return userRepository.findByEmail(email).map(userMapper::toDto);
  }
}
