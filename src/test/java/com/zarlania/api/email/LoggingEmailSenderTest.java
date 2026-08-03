package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The log line is this sender's entire output — it is what stands in for a provider in local
 * development, and the only place a developer can find the verification link — so the line's
 * contents are the contract worth asserting, not an implementation detail.
 */
class LoggingEmailSenderTest {

  private final LoggingEmailSender sender = new LoggingEmailSender();
  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(captured);
    captured.stop();
  }

  @Test
  void logsTheRecipientSubjectAndBodySoAVerificationLinkIsFindableWithoutAProvider() {
    sender.send(
        new EmailMessage(
            "person@example.com",
            "Verify your Zarlania account",
            "Click here: https://zarlania.com/verify-email?token=abc123"));

    assertThat(captured.list).hasSize(1);
    assertThat(captured.list.getFirst().getFormattedMessage())
        .contains("person@example.com")
        .contains("Verify your Zarlania account")
        .contains("https://zarlania.com/verify-email?token=abc123");
  }

  // Every field here originates in registration input, so a crafted value could otherwise forge
  // extra log lines and make the log say whatever its author wanted. One event, one line.
  @Test
  void stripsLineBreaksFromEveryFieldSoCraftedInputCannotForgeExtraLogLines() {
    sender.send(
        new EmailMessage(
            "victim@example.com\r\nERROR: forged recipient line",
            "subject\nwith a break",
            "body\r\nwith breaks"));

    assertThat(captured.list).hasSize(1);
    String line = captured.list.getFirst().getFormattedMessage();
    assertThat(line).doesNotContain("\r").doesNotContain("\n");
    assertThat(line).contains("ERROR: forged recipient line");
  }
}
