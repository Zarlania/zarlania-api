package com.zarlania.api.auth;

import com.zarlania.api.auth.services.TokenKind;
import java.util.UUID;

/**
 * The authenticated identity behind a request: which user is calling, which organization the
 * request acts within, and what kind of token proved it — {@link TokenKind#USER} for every token
 * this service mints today. Nothing enforces the kind yet; a later spec adds the other kinds and
 * the checks that go with them.
 *
 * <p>Produced by {@link SecurityConfig}'s JWT authentication converter and retrieved in controllers
 * via {@code @AuthenticationPrincipal}. A token whose {@code kind} claim names no known kind never
 * reaches here — the converter rejects it as an invalid bearer token.
 */
public record AuthPrincipal(UUID userId, UUID organizationId, TokenKind kind) {}
