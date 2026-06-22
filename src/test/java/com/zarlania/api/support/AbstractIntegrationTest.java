package com.zarlania.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Base for {@code @SpringBootTest} integration tests that commit data to the database. Cleans all
 * application tables after each test method so committed rows don't leak across the shared
 * in-memory H2 instance (which persists for the JVM lifetime via {@code DB_CLOSE_DELAY=-1}).
 */
@SpringBootTest
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
@Sql(scripts = "/sql/clean-database.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public abstract class AbstractIntegrationTest {}
