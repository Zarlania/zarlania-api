package com.zarlania.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /accounts}, validated at the HTTP boundary. The size limits
 * mirror the {@code users.email} (320) and {@code users.username} (100) columns; these bounds also
 * exist as domain invariants in {@code UserService} (defense in depth at two boundaries — the only
 * accepted duplication, since exposing the {@code users} constants here would breach the domain
 * boundary).
 *
 * @param email the new user's email
 * @param username the new user's unique public handle
 */
public record CreateAccountRequest(
    @NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(max = 100) String username) {}
