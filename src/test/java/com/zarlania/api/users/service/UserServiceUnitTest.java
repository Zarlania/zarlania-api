package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.zarlania.api.users.entity.UserEntity;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import com.zarlania.api.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {

  @Mock private UserRepository userRepository;

  @Test
  void createTranslatesEmailUniquenessRaceIntoDomainException() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("race@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("racer")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_USERS_EMAIL\"]"));

    assertThatThrownBy(() -> userService.create("race@example.com", "racer"))
        .isInstanceOf(EmailAlreadyExistsException.class)
        .hasCauseInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void createRethrowsIntegrityViolationUnrelatedToUniqueness() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("long@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("waytoolong")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "Value too long for column \"USERNAME VARCHAR(100)\""));

    assertThatThrownBy(() -> userService.create("long@example.com", "waytoolong"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(EmailAlreadyExistsException.class)
        .isNotInstanceOf(UsernameAlreadyExistsException.class);
  }

  @Test
  void createTranslatesUsernameUniquenessRaceIntoDomainException() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("taken@example.com")).thenReturn(false);
    when(userRepository.existsByUsername("taken")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_USERS_USERNAME\"]"));

    assertThatThrownBy(() -> userService.create("taken@example.com", "taken"))
        .isInstanceOf(UsernameAlreadyExistsException.class)
        .hasCauseInstanceOf(DataIntegrityViolationException.class);
  }
}
