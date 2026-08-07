package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/login}. {@code identifier} may be either an email or a username.
 *
 * @param password capped at the same 128 characters {@code RegisterRequest} accepts, so no
 *     registrable password is ever refused here. The cap is what stops a caller naming a real
 *     account from making the service Argon2-hash a megabyte-long body it could never have stored;
 *     the minimum length is deliberately absent, since rejecting a short password before comparing
 *     it would answer a question about the account rather than about the request.
 */
public record LoginRequest(
    @NotBlank String identifier, @NotBlank @Size(max = 128) String password) {}
