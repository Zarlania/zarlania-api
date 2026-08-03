package com.zarlania.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point.
 *
 * <p>{@code @EnableScheduling} is load-bearing rather than habitual: it drives the token-cleanup
 * sweeps and {@code InMemoryRateLimiter}'s eviction, without which the limiter's map grows for the
 * life of the process on an instance with 512 MB to spend.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ZarlaniaApiApplication {

  /** Boots the application. */
  public static void main(String[] args) {
    SpringApplication.run(ZarlaniaApiApplication.class, args);
  }
}
