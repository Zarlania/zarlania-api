package com.zarlania.api.users.service;

import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.entity.UserEntity;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
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

  private final UserRepository userRepository;
  private final UserMapper userMapper;

  /**
   * Creates a user with the given email and display name.
   *
   * @param email a non-blank email, unique across users
   * @param displayName a non-blank public name other users know this user by
   * @return the created user as a DTO
   * @throws IllegalArgumentException if {@code email} or {@code displayName} is null or blank
   * @throws EmailAlreadyExistsException if a user with that email already exists
   */
  @Transactional
  public User create(String email, String displayName) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("email must not be blank");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    if (userRepository.existsByEmail(email)) {
      throw EmailAlreadyExistsException.forEmail(email);
    }
    UserEntity entity = new UserEntity();
    entity.setEmail(email);
    entity.setDisplayName(displayName);
    try {
      // saveAndFlush forces the INSERT now so a concurrent duplicate that slipped past the
      // existsByEmail pre-check surfaces here as the email unique-constraint violation. Only that
      // specific violation maps to the domain exception; any other integrity failure (e.g. an
      // over-long column) is unrelated to email uniqueness and is rethrown unchanged.
      return userMapper.toDto(userRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException ex) {
      if (isEmailUniquenessViolation(ex)) {
        throw EmailAlreadyExistsException.forEmail(email);
      }
      throw ex;
    }
  }

  /**
   * Reports whether the violation was caused by the email unique constraint. We match the
   * constraint name in the exception's cause chain rather than catching every {@link
   * DataIntegrityViolationException} so unrelated failures are not mislabelled as duplicate emails.
   * Matching the name (which appears in both H2 and PostgreSQL messages) avoids depending on a
   * JPA-provider-specific typed exception.
   */
  private static boolean isEmailUniquenessViolation(DataIntegrityViolationException ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains(EMAIL_UNIQUE_CONSTRAINT)) {
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
