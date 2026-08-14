package com.zarlania.api.auth.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/register}.
 *
 * @param password the maximum is repeated as {@code LoginRequest}'s cap, so that every password
 *     this accepts can still be presented at login. An annotation value has to be a compile-time
 *     constant, so the two are written out rather than shared; lowering one without the other locks
 *     existing accounts out.
 */
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "[a-z0-9-]{3,30}") String username,
    @NotBlank @Size(min = 12, max = 128) String password) {}
