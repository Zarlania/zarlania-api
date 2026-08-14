package com.zarlania.api.auth.dtos;

import java.time.Instant;

/** The raw, one-time-visible secret returned when a refresh-token family is started. */
public record IssuedRefreshToken(String raw, Instant familyExpiresAt) {}
