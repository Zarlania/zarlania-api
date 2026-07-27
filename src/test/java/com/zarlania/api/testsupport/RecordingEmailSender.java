package com.zarlania.api.testsupport;

import com.zarlania.api.common.email.EmailMessage;
import com.zarlania.api.common.email.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double that records every {@link EmailMessage} instead of sending it, so a test can assert
 * on subject/body without a real provider. Register it via {@link RecordingEmailSenderConfig}.
 */
public class RecordingEmailSender implements EmailSender {

  private final List<EmailMessage> messages = new CopyOnWriteArrayList<>();

  @Override
  public void send(EmailMessage message) {
    messages.add(message);
  }

  public List<EmailMessage> messages() {
    return List.copyOf(messages);
  }

  public void clear() {
    messages.clear();
  }
}
