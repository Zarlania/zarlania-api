package com.zarlania.api.email;

/**
 * An outbound email, independent of which provider ultimately sends it.
 *
 * @param to the recipient address. Never logged outside {@link LoggingEmailSender}, which runs only
 *     where no provider is configured: it identifies a person, and a dropped send is traced through
 *     {@code reference} instead.
 * @param subject a short line describing the message, safe to log
 * @param textBody the message itself. Never logged outside {@link LoggingEmailSender}, which runs
 *     only where no provider is configured: a verification email's body carries the raw token.
 * @param reference an opaque handle the caller can recognise this message by in logs, since neither
 *     the address nor the body may appear there. Callers sending on behalf of an account pass that
 *     account's id, so an operator seeing a failed send can look the row up. It must identify the
 *     <em>message</em> without identifying the person — an email address or a name here would put
 *     back exactly what this field exists to keep out.
 */
public record EmailMessage(String to, String subject, String textBody, String reference) {}
