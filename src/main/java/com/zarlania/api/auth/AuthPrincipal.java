package com.zarlania.api.auth;

import java.util.UUID;

/**
 * The authenticated identity behind a request: which user is calling, which organization the
 * request acts within, and what kind of token proved it — {@code user} ({@code TokenKinds.USER})
 * for every token this service mints today. Nothing enforces the value yet; a later spec adds the
 * other kinds and the checks that go with them.
 *
 * <p>Produced by {@link SecurityConfig}'s JWT authentication converter and retrieved in controllers
 * via {@code @AuthenticationPrincipal}.
 */
public record AuthPrincipal(UUID userId, UUID organizationId, String kind) {}
