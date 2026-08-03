package com.zarlania.api.testsupport;

import com.zarlania.api.email.EmailMessage;
import com.zarlania.api.email.EmailSender;
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

  /** Everything sent so far, oldest first, as an immutable snapshot. */
  public List<EmailMessage> messages() {
    return List.copyOf(messages);
  }

  /** Forgets everything recorded. Tests that assert on counts run this between cases. */
  public void clear() {
    messages.clear();
  }
}
