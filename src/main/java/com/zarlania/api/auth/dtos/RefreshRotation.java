package com.zarlania.api.auth.dtos;

import java.time.Instant;
import java.util.UUID;

/** The result of successfully rotating a refresh token: a new raw secret for the same family. */
public record RefreshRotation(
    String newRaw, UUID userId, UUID organizationId, Instant familyExpiresAt) {}
