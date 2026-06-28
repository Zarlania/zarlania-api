package com.zarlania.api.organizations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
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
