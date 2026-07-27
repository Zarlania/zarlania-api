package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/login}. {@code identifier} may be either an email or a username. */
public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {}
