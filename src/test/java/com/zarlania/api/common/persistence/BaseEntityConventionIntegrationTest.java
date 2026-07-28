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

  private static final int NANOS_PER_MICROSECOND = 1_000;

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final UserRepository users;

  // The column is timestamptz(6), so a value that survives a round trip must still carry its
  // microseconds — asserted by reading the row back rather than by inspecting the instance
  // Hibernate populated in memory, which would pass even against a second-precision column.
  @Test
  void saveAssignsUuidAndMicrosecondTimestamps() {
    User saved = users.saveAndFlush(new User("conv@example.com", "convention"));

    User reloaded = users.findById(saved.getId()).orElseThrow();

    assertThat(reloaded.getId()).isEqualTo(saved.getId());
    assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    // Microsecond precision, not nanosecond: timestamptz(6) truncates there, so a stored value
    // always lands on a whole microsecond and matches what Hibernate holds in memory.
    assertThat(reloaded.getCreatedAt().getNano() % NANOS_PER_MICROSECOND).isZero();
  }

  // Against the pre-update value of updated_at, not against created_at: comparing the two
  // timestamps that @CreationTimestamp and @UpdateTimestamp both set on insert passes even if
  // @UpdateTimestamp never fires again, which is exactly the regression worth catching.
  @Test
  void updateMovesUpdatedAtButNotCreatedAt() {
    User saved = users.saveAndFlush(new User("conv2@example.com", "convention2"));
    var created = saved.getCreatedAt();
    var updatedAtBefore = saved.getUpdatedAt();

    saved.markEmailVerified(created.plus(1, ChronoUnit.SECONDS));
    User updated = users.saveAndFlush(saved);

    assertThat(updated.getCreatedAt()).isEqualTo(created);
    assertThat(updated.getUpdatedAt()).isAfter(updatedAtBefore);
  }

  @Test
  void emailUniquenessIsCaseInsensitive() {
    users.saveAndFlush(new User("Case@Example.com", "casetest"));
    assertThat(users.existsByEmail("case@example.com")).isTrue();
  }
}
