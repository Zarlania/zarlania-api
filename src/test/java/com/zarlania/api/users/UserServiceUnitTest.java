package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {

  @Mock private UserRepository users;

  @Test
  void createTranslatesUniqueConstraintRaceIntoDomainException() {
    UserService userService = new UserService(users);
    // Simulate the concurrent-duplicate race: the pre-check passes, but the persisted INSERT
    // trips the database unique constraint. The service must surface the domain exception.
    when(users.existsByEmail("race@example.com")).thenReturn(false);
    when(users.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("uq_users_email"));

    assertThatThrownBy(() -> userService.create("race@example.com"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }
}
