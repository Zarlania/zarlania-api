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

  /**
   * Everything sent so far, oldest first, as an immutable snapshot.
   *
   * <p>Only meaningful to a test whose class owns its Spring context. This bean is a singleton per
   * context, so two classes sharing one see each other's mail — prefer {@link #messagesTo} unless
   * the whole outbound volume is the thing being asserted.
   */
  public List<EmailMessage> messages() {
    return List.copyOf(messages);
  }

  /**
   * Everything sent to one address, oldest first.
   *
   * <p>Scoping by recipient is what lets a test read its own mail without caring what else shares
   * the context: two tests registering different accounts at the same time cannot see each other's
   * verification link, however they interleave.
   */
  public List<EmailMessage> messagesTo(String address) {
    return messages.stream().filter(message -> message.to().equals(address)).toList();
  }

  /** Forgets everything recorded. Tests that assert on counts run this between cases. */
  public void clear() {
    messages.clear();
  }
}
