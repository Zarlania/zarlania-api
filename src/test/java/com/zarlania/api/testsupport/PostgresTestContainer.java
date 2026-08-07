package com.zarlania.api.testsupport;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Single source of truth for the Postgres container every {@code @Testcontainers} integration test
 * spins up via {@code @ServiceConnection}.
 */
public final class PostgresTestContainer {

  // Same major version render.yaml and docker-compose.yml pin for production and local dev.
  private static final String IMAGE = "postgres:17-alpine";

  // @ServiceConnection builds the datasource straight from this container, bypassing
  // application.yml entirely, so its stringtype=unspecified has no effect here — it has
  // to be set again on the container. Without it, pgjdbc binds a String parameter as
  // varchar; Postgres then resolves `citext_column = varchar_param` via an implicit cast
  // to plain text on both sides, so a citext column (email, username) compares
  // case-sensitively instead of case-insensitively as CLAUDE.md's citext convention
  // requires. Leaving the parameter type unspecified lets Postgres infer it from the
  // citext column instead.
  private static final String STRINGTYPE_PARAM = "stringtype";
  private static final String STRINGTYPE_VALUE = "unspecified";

  // Started once for the whole JVM run and deliberately never stopped: Testcontainers' resource
  // reaper removes it at exit. The @Testcontainers extension cannot be used for a container shared
  // across classes, because it stops the container when the class that declared it finishes — so a
  // container held on a shared base class would be torn down by whichever subclass ran first.
  private static final PostgreSQLContainer INSTANCE =
      new PostgreSQLContainer(IMAGE).withUrlParam(STRINGTYPE_PARAM, STRINGTYPE_VALUE);

  static {
    INSTANCE.start();
  }

  private PostgresTestContainer() {}

  /**
   * The one Postgres every test runs against, pre-configured so citext comparisons behave the same
   * as in production.
   *
   * <p>One container for the whole run rather than one per class: Flyway brings the schema to the
   * same state either way, so a container per class costs seconds each and buys nothing. Tests
   * therefore share a database and must not assume an empty one — every test seeds its own account
   * under a unique slug rather than relying on rollback between classes.
   */
  public static PostgreSQLContainer instance() {
    return INSTANCE;
  }
}
