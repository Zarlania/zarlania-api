package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserService.class, UserMapper.class})
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class UserServiceTest {

  @Autowired private UserService userService;

  @Test
  void createPersistsAndReturnsDtoWithId() {
    User created = userService.create("alan@example.com", "alan");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("alan@example.com");
    assertThat(created.username()).isEqualTo("alan");
    assertThat(userService.findById(created.id())).contains(created);
  }

  @Test
  void createRejectsBlankEmail() {
    assertThatThrownBy(() -> userService.create("  ", "name"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsNullEmail() {
    assertThatThrownBy(() -> userService.create(null, "name"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsBlankUsername() {
    assertThatThrownBy(() -> userService.create("noname@example.com", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsNullUsername() {
    assertThatThrownBy(() -> userService.create("noname@example.com", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsOverlongUsername() {
    assertThatThrownBy(() -> userService.create("ok@example.com", "u".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsOverlongEmail() {
    assertThatThrownBy(() -> userService.create("e".repeat(321), "ok"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsDuplicateEmail() {
    userService.create("twin@example.com", "twinOne");

    assertThatThrownBy(() -> userService.create("twin@example.com", "twinTwo"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void createRejectsDuplicateUsername() {
    userService.create("first@example.com", "twin");

    assertThatThrownBy(() -> userService.create("second@example.com", "twin"))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }

  @Test
  void findByEmailReturnsCreatedUser() {
    User created = userService.create("margaret@example.com", "maggie");

    assertThat(userService.findByEmail("margaret@example.com")).contains(created);
  }

  @Test
  void findByIdIsEmptyForUnknownUser() {
    assertThat(userService.findById(UUID.randomUUID())).isEmpty();
  }
}
