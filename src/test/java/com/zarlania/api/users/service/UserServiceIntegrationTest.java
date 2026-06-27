package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

// Fast JPA slice for the service over a real DB. Isolation comes from @DataJpaTest's per-test
// rollback; H2 is pinned centrally via Surefire (pom.xml). AbstractIntegrationTest is the shared
// integration-test anchor, not a cleanup source.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserService.class, UserMapper.class})
class UserServiceIntegrationTest extends AbstractIntegrationTest {

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
