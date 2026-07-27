package com.zarlania.api.common.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailConfig {

  private static final String RESEND_BASE_URL = "https://api.resend.com";
  private static final String PRODUCTION_PROFILE = "production";

  @Bean
  public EmailSender emailSender(
      @Value("${zarlania.email.resend-api-key:}") String apiKey,
      @Value("${zarlania.email.from}") String from,
      Environment environment) {
    boolean production = environment.matchesProfiles(PRODUCTION_PROFILE);
    if (apiKey.isBlank()) {
      if (production) {
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
