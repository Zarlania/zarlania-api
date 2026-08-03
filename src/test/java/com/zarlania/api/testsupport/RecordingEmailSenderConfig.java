package com.zarlania.api.testsupport;

import com.zarlania.api.email.EmailConfig;
import com.zarlania.api.email.EmailSender;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's {@link EmailSender} with {@link RecordingEmailSender} wherever a test
 * {@code @Import}s this class. {@code @Primary} over {@code EmailConfig}'s bean rather than a
 * profile, so the substitution is explicit at each test's import list instead of implicit in
 * whichever profile happens to be active.
 */
@TestConfiguration
public class RecordingEmailSenderConfig {

  /** Replaces the real sender, so a test can assert on what would have been emailed. */
  @Bean
  @Primary
  public RecordingEmailSender recordingEmailSender() {
    return new RecordingEmailSender();
  }

  /**
   * Runs the send on the calling thread, so a test can assert on {@link RecordingEmailSender} the
   * moment the request returns instead of polling for a background thread to catch up. The
   * production executor is asynchronous precisely so the send is <em>not</em> observable in
   * response time; that property belongs to the deployed service, and reproducing it here would
   * only buy flakiness.
   *
   * <p>Carries the same {@code @Qualifier} as {@link EmailConfig}'s bean rather than replacing it
   * by name — bean definition overriding is off by default in Boot. Both beans then match the
   * injection point's qualifier, and {@code @Primary} picks this one.
   */
  @Bean
  @Primary
  @Qualifier(EmailConfig.DISPATCH_EXECUTOR_BEAN)
  public Executor sameThreadEmailDispatchExecutor() {
    return Runnable::run;
  }
}
