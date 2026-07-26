package com.zarlania.api.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class BaseEntityConventionIntegrationTest {

  // stringtype=unspecified matches the production JDBC URL (application.yml):
  // without it, a bound String is typed varchar, and Postgres compares a
  // citext column against it as plain text — case-sensitively.
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withUrlParam("stringtype", "unspecified");

  private final UserRepository users;

  @Test
  void saveAssignsUuidAndMicrosecondTimestamps() {
    User saved = users.saveAndFlush(new User("conv@example.com", "convention"));
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  void updateMovesUpdatedAtButNotCreatedAt() {
    User saved = users.saveAndFlush(new User("conv2@example.com", "convention2"));
    var created = saved.getCreatedAt();
    saved.markEmailVerified(created.plus(1, ChronoUnit.SECONDS));
    User updated = users.saveAndFlush(saved);
    assertThat(updated.getCreatedAt()).isEqualTo(created);
    assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(created);
  }

  @Test
  void emailUniquenessIsCaseInsensitive() {
    users.saveAndFlush(new User("Case@Example.com", "casetest"));
    assertThat(users.existsByEmail("case@example.com")).isTrue();
  }
}
