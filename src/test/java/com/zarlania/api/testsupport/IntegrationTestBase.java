package com.zarlania.api.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests that exercise a component against a real Spring context and a real Postgres — the
 * {@code *IntegrationTest} tier.
 *
 * <p>Points the datasource at the one shared container. Wiring it through {@link
 * DynamicPropertySource} rather than {@code @ServiceConnection} on a {@code @Container} field is
 * what lets the container outlive a single test class: the {@code @Testcontainers} extension stops
 * whatever it manages once the declaring class finishes, which for a container on a shared base
 * means the first subclass to run tears it down for all the rest.
 *
 * <p>Subclasses that need extra configuration declare their own {@code @SpringBootTest(properties =
 * …)}; a local annotation wins over the inherited one, and Spring caches a separate context per
 * distinct configuration.
 */
@SpringBootTest
@Import(TestSupportConfig.class)
public abstract class IntegrationTestBase {

  @DynamicPropertySource
  static void datasourceFromSharedContainer(DynamicPropertyRegistry registry) {
    PostgreSQLContainer postgres = PostgresTestContainer.instance();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
}
