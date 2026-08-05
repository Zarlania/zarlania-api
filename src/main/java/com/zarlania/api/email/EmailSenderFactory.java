package com.zarlania.api.email;

import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Decides which {@link EmailSender} adapter a deployment sends through, and refuses to build one
 * that would silently stop sending.
 *
 * <p>A class of its own rather than a method on {@link EmailConfig}, because the decision is not
 * wiring. "A missing provider key is a startup failure in production but a fall back to logging
 * everywhere else" is a rule about how this service is allowed to run, it has a branch that can be
 * wrong in a way nothing else would report, and it is the piece that changes when the provider
 * changes. {@link EmailConfig} is left holding only bean declarations.
 */
public class EmailSenderFactory {

  private final String apiKey;
  private final String from;
  private final String baseUrl;
  private final Environment environment;

  /**
   * @param apiKey the provider credential, blank when none is configured
   * @param from the address every message is sent from
   * @param baseUrl the provider's API root, configurable so a different Resend-compatible endpoint
   *     — or a stub — can be pointed at without editing this class
   * @param environment consulted only for the active profiles, to decide whether a missing key is
   *     fatal
   */
  public EmailSenderFactory(String apiKey, String from, String baseUrl, Environment environment) {
    this.apiKey = apiKey;
    this.from = from;
    this.baseUrl = baseUrl;
    this.environment = environment;
  }

  /**
   * Builds the adapter this deployment should send through: the real provider where a key is
   * configured, the logging fallback otherwise.
   *
   * <p>The returned sender is bare. Callers are expected to wrap it in the service-wide budget —
   * {@link EmailConfig} is the only one, and does.
   *
   * @throws IllegalStateException in production when no key is configured. A deployment that
   *     quietly logged its verification emails instead of sending them would look healthy while
   *     stranding every new account, so refusing to start is the milder failure.
   */
  public EmailSender create() {
    if (apiKey.isBlank()) {
      if (environment.matchesProfiles(EmailConfig.PRODUCTION_PROFILE)) {
        throw new IllegalStateException("RESEND_API_KEY must be set in production");
      }
      return new LoggingEmailSender();
    }
    RestClient client =
        RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    return new ResendEmailSender(client, from);
  }
}
