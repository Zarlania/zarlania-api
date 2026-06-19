package com.zarlania.api.organizations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class OrganizationsSchemaTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flywayCreatesEmptyOrganizationsTable() {
    Integer count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organizations", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void flywayCreatesEmptyMembershipsTable() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM memberships", Integer.class);
    assertThat(count).isZero();
  }
}
