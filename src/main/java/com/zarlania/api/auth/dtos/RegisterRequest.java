package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /auth/register}. */
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "[a-z0-9-]{3,30}") String username,
    @NotBlank @Size(min = 12, max = 128) String password) {}
