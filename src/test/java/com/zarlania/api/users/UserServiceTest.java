package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserService.class})
class UserServiceTest {

  @Autowired private UserService userService;

  @Test
  void createPersistsAndReturnsDtoWithId() {
    UserDto created = userService.create("alan@example.com");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("alan@example.com");
    assertThat(userService.findById(created.id())).contains(created);
  }

  @Test
  void createRejectsBlankEmail() {
    assertThatThrownBy(() -> userService.create("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsDuplicateEmail() {
    userService.create("twin@example.com");

    assertThatThrownBy(() -> userService.create("twin@example.com"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void findByEmailReturnsCreatedUser() {
    UserDto created = userService.create("margaret@example.com");

    assertThat(userService.findByEmail("margaret@example.com")).contains(created);
  }

  @Test
  void findByIdIsEmptyForUnknownUser() {
    assertThat(userService.findById(UUID.randomUUID())).isEmpty();
  }
}
