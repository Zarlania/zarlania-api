package com.zarlania.api.email;

import lombok.extern.slf4j.Slf4j;

/**
 * Local/dev fallback that logs instead of sending. Used when no Resend API key is configured, so
 * the application still starts and email content (including verification links) is visible for
 * development without a provider account.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

  @Override
  public void send(EmailMessage message) {
    log.info(
        "Email to {} — subject: {} — body: {}",
        stripLineBreaks(message.to()),
        stripLineBreaks(message.subject()),
        stripLineBreaks(message.textBody()));
  }

  /**
   * Recipient, subject and body all originate from registration input, so a crafted value
   * containing a line break could forge extra log lines. Strip line breaks before they reach the
   * logger rather than trust the caller.
   */
  private static String stripLineBreaks(String value) {
    return value.replace("\r", "").replace("\n", " ");
  }
}
