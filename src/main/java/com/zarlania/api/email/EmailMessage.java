package com.zarlania.api.email;

/** An outbound email, independent of which provider ultimately sends it. */
public record EmailMessage(String to, String subject, String textBody) {}
