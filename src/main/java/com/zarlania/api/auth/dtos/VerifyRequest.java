package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/verify}. */
public record VerifyRequest(@NotBlank String token) {}
