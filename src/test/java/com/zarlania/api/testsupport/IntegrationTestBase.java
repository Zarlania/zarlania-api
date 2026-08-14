package com.zarlania.api.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
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
 * <p><strong>Every test method gets its own Spring context.</strong> The application is full of
 * singletons holding mutable state — the rate limiter's buckets, the email budget's counter, the
 * recording email sender's outbox — and a context cached across methods makes each of them a
 * channel one test can use to change what another observes. Dirtying after every method closes that
 * channel outright, so a method's assertions depend only on what that method did. It is the
 * expensive choice, and taken deliberately: a context rebuild costs seconds, whereas a test that
 * passes only because of the order it ran in costs an afternoon and, once parallelism is switched
 * on, stops being reproducible at all.
 *
 * <p>The database is emphatically <em>not</em> reset with the context — one Postgres container
 * serves the whole run, and committed rows outlive the context that wrote them. Seed under a unique
 * slug rather than expecting an empty schema; see {@link PostgresTestContainer}.
 *
 * <p>Subclasses that need extra configuration declare their own {@code @SpringBootTest(properties =
 * …)}; a local annotation wins over the inherited one.
 */
@SpringBootTest
@Import(TestSupportConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public abstract class IntegrationTestBase {

  @DynamicPropertySource
  static void datasourceFromSharedContainer(DynamicPropertyRegistry registry) {
    PostgreSQLContainer postgres = PostgresTestContainer.instance();
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
}
