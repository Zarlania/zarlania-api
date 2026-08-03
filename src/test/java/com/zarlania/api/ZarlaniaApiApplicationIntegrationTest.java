package com.zarlania.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.IntegrationTestBase;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor(onConstructor_ = @Autowired)
class ZarlaniaApiApplicationIntegrationTest extends IntegrationTestBase {

  private final JdbcTemplate jdbcTemplate;

  @Test
  void applicationBootsAgainstPostgres() {
    Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    assertThat(result).isEqualTo(1);
  }

  @Test
  void flywayCreatesItsSchemaHistoryTable() {
    Integer tables =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name = 'flyway_schema_history'",
            Integer.class);
    assertThat(tables).isEqualTo(1);
  }

  @Test
  void migrationCreatesTheAccountTables() {
    Integer tables =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name IN"
                + " ('users','organizations','organization_memberships',"
                + " 'password_credentials','email_verification_tokens','refresh_tokens')",
            Integer.class);
    assertThat(tables).isEqualTo(6);
  }
}
