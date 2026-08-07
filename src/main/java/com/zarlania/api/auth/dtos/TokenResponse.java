package com.zarlania.api.auth.dtos;

/**
 * Body returned by {@code POST /auth/login} and {@code POST /auth/refresh}. The refresh token never
 * appears here — it travels only as the {@code zarlania_refresh} cookie.
 */
public record TokenResponse(String accessToken) {}
