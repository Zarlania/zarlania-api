package com.zarlania.api.support;

import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for service- and repository-layer integration tests — the tests that verify behavior against
 * a real database (e.g. that a row was actually persisted).
 *
 * <p>Deliberately slice-agnostic: it carries shared configuration only and does <em>not</em> impose
 * a context-loading annotation. Each subclass keeps its own slice — {@code @DataJpaTest} for the
 * fast JPA slice (with {@code TestEntityManager} and per-test rollback) or {@code @SpringBootTest}
 * for cross-domain orchestration — while inheriting the shared H2 pin and between-test cleanup.
 *
 * <p>{@link CleanDatabaseTestExecutionListener} truncates every table after each method so
 * committed rows can't leak across the shared in-memory H2 instance.
 */
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
@TestExecutionListeners(
    listeners = CleanDatabaseTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractIntegrationTest {}
