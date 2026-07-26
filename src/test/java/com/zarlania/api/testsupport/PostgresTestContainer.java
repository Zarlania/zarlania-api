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

  private PostgresTestContainer() {}

  /** A container pre-configured so citext comparisons behave the same as in production. */
  public static PostgreSQLContainer create() {
    return new PostgreSQLContainer(IMAGE).withUrlParam(STRINGTYPE_PARAM, STRINGTYPE_VALUE);
  }
}
