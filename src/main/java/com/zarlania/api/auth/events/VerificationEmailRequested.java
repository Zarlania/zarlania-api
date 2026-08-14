package com.zarlania.api.auth.events;

import java.util.UUID;

/**
 * Published after a new registration commits, carrying the raw verification token so {@link
 * com.zarlania.api.auth.services.RegistrationEmailListener RegistrationEmailListener} can compose
 * the verification link.
 *
 * <p>Named for what happened rather than for what should follow, so that a second consumer added
 * later is a new listener rather than a rename: the publisher records the fact, and who acts on it
 * is not its concern. The qualified link above rather than an import is deliberate — an event
 * importing its own consumer would put a cycle between this package and {@code auth.services}.
 *
 * @param email where the link is sent. Never logged — it identifies a person.
 * @param rawToken the token in the form that goes in the link. Never logged, never stored: only its
 *     hash is persisted, so a log line holding this is a usable credential.
 * @param userId the account being verified, used as the sent message's log reference so a failed
 *     send is traceable without the address appearing in the logs
 */
public record VerificationEmailRequested(String email, String rawToken, UUID userId) {}
