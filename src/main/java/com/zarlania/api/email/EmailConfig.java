package com.zarlania.api.email;

import com.zarlania.api.throttle.RateLimiter;
import com.zarlania.api.throttle.ThrottleProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Declares the outbound email beans: the sender the application injects, and the thread its sends
 * happen on.
 *
 * <p>Wiring only. Which adapter a deployment gets, and when a missing key is fatal, belong to
 * {@link EmailSenderFactory}; the values themselves belong to {@link EmailProperties}.
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
      EmailProperties emailProperties,
      Environment environment,
      RateLimiter rateLimiter,
      ThrottleProperties throttleProperties) {
    return new BudgetedEmailSender(
        new EmailSenderFactory(emailProperties, environment).create(),
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
   * <p>Both the pool size and the queue bound are configuration rather than constants, because both
   * are sizing decisions that change with the instance and the plan; see {@link EmailProperties}.
   */
  @Bean(DISPATCH_EXECUTOR_BEAN)
  public ThreadPoolTaskExecutor emailDispatchExecutor(EmailProperties emailProperties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(emailProperties.dispatchThreads());
    executor.setMaxPoolSize(emailProperties.dispatchThreads());
    executor.setQueueCapacity(emailProperties.dispatchQueueCapacity());
    executor.setThreadNamePrefix(DISPATCH_THREAD_PREFIX);
    // A send already in flight at shutdown has been counted against the email budget and is
    // somebody's verification link; letting it finish costs a few seconds of drain time.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(DISPATCH_SHUTDOWN_GRACE_SECONDS);
    return executor;
  }
}
