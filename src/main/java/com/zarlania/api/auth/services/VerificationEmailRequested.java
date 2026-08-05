package com.zarlania.api.auth.services;

import java.util.UUID;

/**
 * Published after a new registration commits, carrying the raw verification token so {@link
 * RegistrationEmailListener} can compose the verification link.
 *
 * @param email where the link is sent. Never logged — it identifies a person.
 * @param rawToken the token in the form that goes in the link. Never logged, never stored: only its
 *     hash is persisted, so a log line holding this is a usable credential.
 * @param userId the account being verified, used as the sent message's log reference so a failed
 *     send is traceable without the address appearing in the logs
 */
record VerificationEmailRequested(String email, String rawToken, UUID userId) {}
