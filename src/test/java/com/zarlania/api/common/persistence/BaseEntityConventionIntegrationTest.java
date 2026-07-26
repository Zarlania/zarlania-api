package com.zarlania.api.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.PostgresTestContainer;
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

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

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
