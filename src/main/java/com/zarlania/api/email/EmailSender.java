package com.zarlania.api.email;

/** Port for sending email, implemented by a real provider adapter or a local fallback. */
public interface EmailSender {

  /**
   * Sends one message, or throws.
   *
   * <p>Synchronous and blocking by contract. Callers that must not wait on a provider — anything on
   * a request thread — are responsible for handing this to an executor.
   *
   * @throws RuntimeException if the provider refuses or is unreachable; the concrete type depends
   *     on the adapter, so callers that must not fail should catch broadly and log
   */
  void send(EmailMessage message);
}
