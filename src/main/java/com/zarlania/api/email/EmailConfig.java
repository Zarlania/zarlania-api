package com.zarlania.api.email;

import com.zarlania.api.throttle.RateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * Chooses the {@link EmailSender} for this environment and wraps it in the service-wide budget.
 *
 * <p>Which adapter depends on whether a provider key is configured, and a missing key is a startup
 * failure in production rather than a silent fall back to logging — a deployment that quietly stops
 * sending real verification emails is worse than one that will not start.
 */
@Configuration
public class EmailConfig {

  private static final String RESEND_BASE_URL = "https://api.resend.com";
  private static final String PRODUCTION_PROFILE = "production";

  public static final String DISPATCH_EXECUTOR_BEAN = "emailDispatchExecutor";

  private static final int DISPATCH_THREADS = 1;
  private static final String DISPATCH_THREAD_PREFIX = "email-dispatch-";
  private static final int DISPATCH_SHUTDOWN_GRACE_SECONDS = 10;

  /**
   * The application's email sender: a real provider where one is configured, a logging adapter
   * otherwise, always inside the service-wide budget.
   *
   * <p>Wrapped rather than applied at the one caller that sends mail today, because the budget is a
   * property of the outbound channel — every future caller inherits it without having to remember
   * to.
   *
   * @throws IllegalStateException in production if no provider key is configured
   */
  @Bean
  public EmailSender emailSender(
      @Value("${zarlania.email.resend-api-key:}") String apiKey,
      @Value("${zarlania.email.from}") String from,
      Environment environment,
      RateLimiter rateLimiter,
      ThrottleProperties throttleProperties) {
    return new BudgetedEmailSender(
        provider(apiKey, from, environment),
        rateLimiter,
        throttleProperties.emailBudgetLimit(),
        throttleProperties.emailBudgetWindow());
  }

  /**
   * The single thread email is dispatched on, so no request thread ever waits on a provider.
   *
   * <p>Off the request thread for enumeration safety, not for throughput. {@code
   * RegistrationService} pays a decoy Argon2 hash on every early-return branch so that "unknown
   * address", "already verified" and "verification resent" cannot be told apart by how long the
   * resend endpoint takes to answer — but only the third of those actually sends anything, and a
   * provider HTTP round trip taken inline costs far more than the hash the other two pay. That
   * difference alone would re-open the channel the decoy exists to close. Handing every send here
   * makes all three branches cost the same whatever the provider does.
   *
   * <p>One thread, because {@link BudgetedEmailSender} caps the entire service at {@code
   * zarlania.throttle.email-budget-limit} messages per window: there is no volume here worth
   * parallelising, and a second thread would only add heap pressure on a 512 MB instance.
   *
   * @param queueCapacity bounded for the same reason — an unbounded queue turns a provider outage
   *     into an out-of-memory kill, while a full one rejects and {@code RegistrationEmailListener}
   *     logs the dropped message
   */
  @Bean(DISPATCH_EXECUTOR_BEAN)
  public ThreadPoolTaskExecutor emailDispatchExecutor(
      @Value("${zarlania.email.dispatch-queue-capacity}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(DISPATCH_THREADS);
    executor.setMaxPoolSize(DISPATCH_THREADS);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix(DISPATCH_THREAD_PREFIX);
    // A send already in flight at shutdown has been counted against the email budget and is
    // somebody's verification link; letting it finish costs a few seconds of drain time.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(DISPATCH_SHUTDOWN_GRACE_SECONDS);
    return executor;
  }

  /**
   * Package-private rather than private so ResendEmailSenderTest can assert which provider is
   * chosen without unwrapping the budget decorator the bean method above always applies.
   */
  EmailSender provider(String apiKey, String from, Environment environment) {
    if (apiKey.isBlank()) {
      if (environment.matchesProfiles(PRODUCTION_PROFILE)) {
        throw new IllegalStateException("RESEND_API_KEY must be set in production");
      }
      return new LoggingEmailSender();
    }
    RestClient client =
        RestClient.builder()
            .baseUrl(RESEND_BASE_URL)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    return new ResendEmailSender(client, from);
  }
}
