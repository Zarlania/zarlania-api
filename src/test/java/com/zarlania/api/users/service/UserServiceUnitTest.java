package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.zarlania.api.users.entity.UserEntity;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
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
    lenient().when(userRepository.existsByUsername("racer")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "could not execute statement [Unique index or primary key violation: "
                    + "\"PUBLIC.UQ_USERS_EMAIL\"]"));

    assertThatThrownBy(() -> userService.create("race@example.com", "racer"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void createRethrowsIntegrityViolationUnrelatedToUniqueness() {
    UserService userService = new UserService(userRepository, new UserMapper());
    when(userRepository.existsByEmail("long@example.com")).thenReturn(false);
    lenient().when(userRepository.existsByUsername("waytoolong")).thenReturn(false);
    when(userRepository.saveAndFlush(any(UserEntity.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "Value too long for column \"USERNAME VARCHAR(100)\""));

    assertThatThrownBy(() -> userService.create("long@example.com", "waytoolong"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(EmailAlreadyExistsException.class);
  }
}
