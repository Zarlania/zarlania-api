package com.zarlania.api.auth.services;

/**
 * Published when a registration attempt targets an email that is already registered, so the account
 * owner can be notified instead of leaking that fact back to the caller.
 */
record DuplicateRegistrationAttempted(String email) {}
