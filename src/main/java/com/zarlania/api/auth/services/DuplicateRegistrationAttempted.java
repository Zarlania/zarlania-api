package com.zarlania.api.auth.services;

import java.util.UUID;

/**
 * Published when a registration attempt targets an email that is already registered, so the account
 * owner can be notified instead of leaking that fact back to the caller.
 *
 * @param email the existing owner's address. Never logged — it identifies a person.
 * @param userId the account that already holds the address, used as the sent message's log
 *     reference so a failed send is traceable without the address appearing in the logs
 */
record DuplicateRegistrationAttempted(String email, UUID userId) {}
