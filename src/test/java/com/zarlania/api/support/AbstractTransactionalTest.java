package com.zarlania.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;

/**
 * Base for the serial {@code *TransactionalTest} suite: integration tests that must let the code
 * under test commit or roll back for real, so they cannot run inside a wrapping test transaction
 * (e.g. asserting that an atomic operation actually undid its first step). Because they commit,
 * they can't be isolated by rollback and aren't parallel-safe — they run in their own non-parallel
 * CI suite, kept small and scenario-specific.
 *
 * <p>{@link CleanDatabaseTestExecutionListener} truncates every table after each method so these
 * committing tests don't leak into one another. The H2 datasource is pinned centrally via the
 * Surefire {@code spring.datasource.url} system property (see {@code pom.xml}).
 */
@SpringBootTest
@TestExecutionListeners(
    listeners = CleanDatabaseTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractTransactionalTest {}
