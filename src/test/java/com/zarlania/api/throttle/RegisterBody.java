package com.zarlania.api.throttle;

/**
 * Stands in for the real registration request body in throttle tests. Names neither of its
 * components {@code identifier}, which is what proves {@link AccountIdentifierReader} reads
 * whichever component the endpoint names rather than a fixed one.
 */
record RegisterBody(String email, String username) {}
