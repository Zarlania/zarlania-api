package com.zarlania.api.throttle;

/**
 * Stands in for the real login request body in throttle tests, which only ever need a record that
 * declares an {@code identifier} component for {@link AccountIdentifierReader} to find.
 *
 * <p>Carries {@code password} as well as {@code identifier} so the reader is exercised against a
 * body with more than one component — a single-component record would pass even if the reader
 * ignored the requested name and simply took the first one.
 */
record LoginBody(String identifier, String password) {}
