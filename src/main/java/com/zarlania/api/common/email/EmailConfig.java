package com.zarlania.api.common.email;

import com.zarlania.api.common.throttle.RateLimiter;
import com.zarlania.api.common.throttle.ThrottleProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailConfig {

  private static final String RESEND_BASE_URL = "https://api.resend.com";
  private static final String PRODUCTION_PROFILE = "production";

  // Wrapped rather than applied at the one caller that sends mail today: the budget is a property
  // of the outbound channel, so every future caller inherits it without having to remember to.
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

  // Package-private rather than private so ResendEmailSenderTest can assert which provider is
  // chosen without unwrapping the budget decorator the bean method above always applies.
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
