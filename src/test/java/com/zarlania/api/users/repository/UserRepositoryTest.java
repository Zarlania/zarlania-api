package com.zarlania.api.users.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.users.entity.UserEntity;
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

  @Autowired private UserRepository userRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void savingAssignsIdAndAuditTimestamps() {
    UserEntity user = new UserEntity();
    user.setEmail("ada@example.com");
    user.setUsername("ada");

    UserEntity saved = userRepository.save(user);
    entityManager.flush();

    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void findByEmailReturnsTheSavedUser() {
    UserEntity user = new UserEntity();
    user.setEmail("grace@example.com");
    user.setUsername("grace");
    userRepository.save(user);
    entityManager.flush();

    Optional<UserEntity> found = userRepository.findByEmail("grace@example.com");

    assertThat(found).isPresent();
    assertThat(found.get().getEmail()).isEqualTo("grace@example.com");
  }

  @Test
  void existsByEmailReflectsPersistedState() {
    assertThat(userRepository.existsByEmail("none@example.com")).isFalse();
    UserEntity user = new UserEntity();
    user.setEmail("none@example.com");
    user.setUsername("nobody");
    userRepository.save(user);
    entityManager.flush();

    assertThat(userRepository.existsByEmail("none@example.com")).isTrue();
  }

  @Test
  void duplicateEmailViolatesTheUniqueConstraint() {
    UserEntity first = new UserEntity();
    first.setEmail("dup@example.com");
    first.setUsername("dupOne");
    userRepository.save(first);
    entityManager.flush();

    UserEntity second = new UserEntity();
    second.setEmail("dup@example.com");
    second.setUsername("dupTwo");

    assertThatThrownBy(() -> userRepository.saveAndFlush(second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
