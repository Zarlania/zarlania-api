package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class UserRepositoryTest {

  @Autowired private UserRepository users;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savingAssignsIdAndAuditTimestamps() {
    User user = new User();
    user.setEmail("ada@example.com");

    User saved = users.save(user);
    entityManager.flush();

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByEmailReturnsTheSavedUser() {
    User user = new User();
    user.setEmail("grace@example.com");
    users.save(user);
    entityManager.flush();

    Optional<User> found = users.findByEmail("grace@example.com");

    assertThat(found).isPresent();
    assertThat(found.get().getEmail()).isEqualTo("grace@example.com");
  }

  @Test
  void existsByEmailReflectsPersistedState() {
    assertThat(users.existsByEmail("none@example.com")).isFalse();
    User user = new User();
    user.setEmail("none@example.com");
    users.save(user);
    entityManager.flush();

    assertThat(users.existsByEmail("none@example.com")).isTrue();
  }

  @Test
  void duplicateEmailViolatesTheUniqueConstraint() {
    User first = new User();
    first.setEmail("dup@example.com");
    users.save(first);
    entityManager.flush();

    User second = new User();
    second.setEmail("dup@example.com");

    assertThatThrownBy(() -> users.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
