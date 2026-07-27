package com.zarlania.api.auth.services;

/**
 * Published after a new registration commits, carrying the raw verification token so {@link
 * RegistrationEmailListener} can compose the verification link. Never logged — see {@link
 * RegistrationEmailListener}.
 */
record VerificationEmailRequested(String email, String rawToken) {}
