package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
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
    User created = userService.create("alan@example.com", "Alan T");

    assertThat(created.id()).isNotNull();
    assertThat(created.email()).isEqualTo("alan@example.com");
    assertThat(created.displayName()).isEqualTo("Alan T");
    assertThat(userService.findById(created.id())).contains(created);
  }

  @Test
  void createRejectsBlankEmail() {
    assertThatThrownBy(() -> userService.create("  ", "Name"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsNullEmail() {
    assertThatThrownBy(() -> userService.create(null, "Name"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsBlankDisplayName() {
    assertThatThrownBy(() -> userService.create("noname@example.com", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsNullDisplayName() {
    assertThatThrownBy(() -> userService.create("noname@example.com", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createRejectsDuplicateEmail() {
    userService.create("twin@example.com", "Twin One");

    assertThatThrownBy(() -> userService.create("twin@example.com", "Twin Two"))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void findByEmailReturnsCreatedUser() {
    User created = userService.create("margaret@example.com", "Maggie");

    assertThat(userService.findByEmail("margaret@example.com")).contains(created);
  }

  @Test
  void findByIdIsEmptyForUnknownUser() {
    assertThat(userService.findById(UUID.randomUUID())).isEmpty();
  }
}
