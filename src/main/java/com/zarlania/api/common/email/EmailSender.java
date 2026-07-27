package com.zarlania.api.common.email;

/** Port for sending email, implemented by a real provider adapter or a local fallback. */
public interface EmailSender {

  void send(EmailMessage message);
}
