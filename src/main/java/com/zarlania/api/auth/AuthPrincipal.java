package com.zarlania.api.auth;

import java.util.UUID;

/**
 * The authenticated identity behind a request: which user is calling, which organization the
 * request acts within, and what kind of token proved it (e.g. {@code access}).
 *
 * <p>Produced by {@link SecurityConfig}'s JWT authentication converter and retrieved in controllers
 * via {@code @AuthenticationPrincipal}.
 */
public record AuthPrincipal(UUID userId, UUID organizationId, String kind) {}
