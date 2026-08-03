package com.zarlania.api.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers the test-only collaborators every Spring-backed test may inject.
 *
 * <p>Imported from {@link IntegrationTestBase}, so a test that needs {@link TestAccounts} simply
 * declares it as a dependency. Kept as a configuration class rather than component-scanned, because
 * component scanning test helpers into the application's own scan would make them reachable from
 * production code by accident.
 */
@TestConfiguration
@Import({TestAccounts.class, AccountAssertions.class})
public class TestSupportConfig {}
