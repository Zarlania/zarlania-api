package com.zarlania.api.email;

import java.net.http.HttpClient;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

  private final EmailProperties emailProperties;
  private final Environment environment;

  /**
   * @param emailProperties the provider credential, address and timeouts this deployment is
   *     configured with
   * @param environment consulted only for the active profiles, to decide whether a missing key is
   *     fatal
   */
  public EmailSenderFactory(EmailProperties emailProperties, Environment environment) {
    this.emailProperties = emailProperties;
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
    if (emailProperties.resendApiKey().isBlank()) {
      if (environment.matchesProfiles(EmailConfig.PRODUCTION_PROFILE)) {
        throw new IllegalStateException("RESEND_API_KEY must be set in production");
      }
      return new LoggingEmailSender();
    }
    RestClient client =
        RestClient.builder()
            .requestFactory(timeoutBoundRequestFactory())
            .baseUrl(emailProperties.resendBaseUrl())
            .defaultHeader("Authorization", "Bearer " + emailProperties.resendApiKey())
            .build();
    return new ResendEmailSender(client, emailProperties.from());
  }

  /**
   * A request factory that gives up rather than waiting indefinitely. {@code RestClient.builder()}
   * would otherwise detect one with no timeouts at all, and because email is dispatched on a very
   * small pool, one send that waits forever stalls every message queued behind it — a service that
   * has silently stopped sending verification email while looking healthy.
   */
  private JdkClientHttpRequestFactory timeoutBoundRequestFactory() {
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(emailProperties.connectTimeout()).build());
    requestFactory.setReadTimeout(emailProperties.readTimeout());
    return requestFactory;
  }
}
