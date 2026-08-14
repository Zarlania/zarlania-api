package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /auth/resend}. */
public record ResendRequest(@NotBlank @Email String email) {}
