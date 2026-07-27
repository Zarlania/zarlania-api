package com.zarlania.api.testsupport;

import com.zarlania.api.common.email.EmailSender;
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

  @Bean
  @Primary
  public RecordingEmailSender recordingEmailSender() {
    return new RecordingEmailSender();
  }
}
