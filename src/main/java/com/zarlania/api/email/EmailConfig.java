package com.zarlania.api.email;

import com.zarlania.api.throttle.RateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Declares the outbound email beans: the sender the application injects, and the thread its sends
 * happen on.
 *
 * <p>Wiring only. Which adapter a deployment gets, and when a missing key is fatal, belong to
 * {@link EmailSenderFactory}.
 */
@Configuration
public class EmailConfig {

  public static final String DISPATCH_EXECUTOR_BEAN = "emailDispatchExecutor";

  static final String PRODUCTION_PROFILE = "production";

  private static final String DISPATCH_THREAD_PREFIX = "email-dispatch-";
  private static final int DISPATCH_SHUTDOWN_GRACE_SECONDS = 10;

  /**
   * The application's email sender: whichever adapter {@link EmailSenderFactory} selects, always
   * inside the service-wide budget.
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
      @Value("${zarlania.email.resend-base-url}") String baseUrl,
      Environment environment,
      RateLimiter rateLimiter,
      ThrottleProperties throttleProperties) {
    return new BudgetedEmailSender(
        new EmailSenderFactory(apiKey, from, baseUrl, environment).create(),
        rateLimiter,
        throttleProperties.emailBudgetLimit(),
        throttleProperties.emailBudgetWindow());
  }

  /**
   * The thread pool email is dispatched on, so no request thread ever waits on a provider.
   *
   * <p>Off the request thread for enumeration safety, not for throughput. {@code
   * RegistrationService} pays a decoy Argon2 hash on every early-return branch so that "unknown
   * address", "already verified" and "verification resent" cannot be told apart by how long the
   * resend endpoint takes to answer — but only the third of those actually sends anything, and a
   * provider HTTP round trip taken inline costs far more than the hash the other two pay. That
   * difference alone would re-open the channel the decoy exists to close. Handing every send here
   * makes all three branches cost the same whatever the provider does.
   *
   * @param threads how many sends may be in flight at once. One is enough while {@link
   *     BudgetedEmailSender} caps the whole service at {@code zarlania.throttle.email-budget-limit}
   *     messages per window — there is no volume worth parallelising, and each extra thread is heap
   *     an instance this size has to find. It is configuration rather than a constant because both
   *     halves of that reasoning are sizing decisions that change with the plan.
   * @param queueCapacity bounded for the same reason — an unbounded queue turns a provider outage
   *     into an out-of-memory kill, while a full one rejects and {@code EmailDispatcher} logs the
   *     dropped message
   */
  @Bean(DISPATCH_EXECUTOR_BEAN)
  public ThreadPoolTaskExecutor emailDispatchExecutor(
      @Value("${zarlania.email.dispatch-threads}") int threads,
      @Value("${zarlania.email.dispatch-queue-capacity}") int queueCapacity) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix(DISPATCH_THREAD_PREFIX);
    // A send already in flight at shutdown has been counted against the email budget and is
    // somebody's verification link; letting it finish costs a few seconds of drain time.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(DISPATCH_SHUTDOWN_GRACE_SECONDS);
    return executor;
  }
}
