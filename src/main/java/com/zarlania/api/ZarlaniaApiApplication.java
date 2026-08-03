package com.zarlania.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
// Drives InMemoryRateLimiter's periodic eviction sweep (throttle).
@EnableScheduling
public class ZarlaniaApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ZarlaniaApiApplication.class, args);
  }
}
