package com.zarlania.api.email;

import com.zarlania.api.logging.LogSanitizer;
import lombok.extern.slf4j.Slf4j;

/**
 * Local/dev fallback that logs instead of sending. Used when no Resend API key is configured, so
 * the application still starts and email content (including verification links) is visible for
 * development without a provider account.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

  /**
   * {@inheritDoc}
   *
   * <p>Recipient, subject and body all originate from registration input, so each is folded onto
   * one line before it reaches the logger rather than trusting the caller. This is also the one
   * place in the application where a recipient address and a raw verification token are written to
   * a log at all — acceptable only because it runs solely where no provider is configured.
   */
  @Override
  public void send(EmailMessage message) {
    log.info(
        "Email to {} — subject: {} — body: {}",
        LogSanitizer.singleLine(message.to()),
        LogSanitizer.singleLine(message.subject()),
        LogSanitizer.singleLine(message.textBody()));
  }
}
