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
   * <p>This bean is a singleton per Spring context and every test method gets its own context, so
   * what this returns is exactly what the running method caused — total outbound volume is a fair
   * thing to assert on.
   */
  public List<EmailMessage> messages() {
    return List.copyOf(messages);
  }

  /**
   * Everything sent to one address, oldest first.
   *
   * <p>Scoping by recipient is what lets a method that registers several accounts pick out one
   * account's verification link rather than whichever was sent last.
   */
  public List<EmailMessage> messagesTo(String address) {
    return messages.stream().filter(message -> message.to().equals(address)).toList();
  }

  /**
   * Forgets everything recorded. For a method that sends mail while arranging its subject and then
   * wants to count only what the subject itself sends — the recorder already starts empty, so there
   * is nothing to clear between methods.
   */
  public void clear() {
    messages.clear();
  }
}
