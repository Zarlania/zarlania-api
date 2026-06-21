package com.zarlania.api.users.dto;

import java.util.UUID;

/**
 * Immutable view of a user for use across the domain boundary and in API responses. This DTO — not
 * the JPA {@code UserEntity} — is the type passed throughout the application; mapping from the
 * entity lives in the {@code users} service layer, not on this type.
 *
 * @param id the user's id
 * @param email the user's email
 * @param username the user's unique public handle (not PII)
 */
public record User(UUID id, String email, String username) {}
