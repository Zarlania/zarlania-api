package com.zarlania.api.testsupport;

import java.util.UUID;

/**
 * One account seeded by {@link TestAccounts}, and the ids or hashes a test needs to assert on what
 * became of it. Hashes rather than raw tokens, because that is what the rows hold — see {@link
 * AccountAssertions}.
 *
 * @param verificationTokenHash the hash of an outstanding verification token
 * @param refreshTokenHash the hash of a live refresh token, as a logged-in account would have
 */
public record SeededAccount(
    UUID userId,
    UUID organizationId,
    String email,
    String username,
    String verificationTokenHash,
    String refreshTokenHash) {}
