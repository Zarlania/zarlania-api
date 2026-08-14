package com.zarlania.api.auth.services;

import java.util.UUID;

/** The ids a seeded account's minted token is expected to carry. */
record SeededSession(UUID userId, UUID organizationId) {}
