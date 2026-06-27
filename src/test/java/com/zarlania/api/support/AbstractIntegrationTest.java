package com.zarlania.api.support;

/**
 * Shared base for service- and repository-layer integration tests — the tests that verify behavior
 * against a real database (e.g. that a row was actually persisted).
 *
 * <p>Deliberately slice-agnostic: it imposes no context-loading annotation, so each subclass keeps
 * its own slice — {@code @DataJpaTest} for the fast JPA slice (with {@code TestEntityManager} and
 * per-test rollback, which also provides isolation) or {@code @SpringBootTest} for cross-domain
 * orchestration. The H2 datasource is pinned centrally for the whole test run via the Surefire
 * {@code spring.datasource.url} system property (see {@code pom.xml}), so it isn't repeated here.
 *
 * <p>This base currently holds no behavior; it exists as the common anchor where shared
 * integration-test helpers live as they emerge.
 */
public abstract class AbstractIntegrationTest {}
