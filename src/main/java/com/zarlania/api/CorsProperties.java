package com.zarlania.api;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable CORS allowlist. Bound from {@code zarlania.cors.*}. */
@ConfigurationProperties(prefix = "zarlania.cors")
public record CorsProperties(List<String> allowedOrigins) {

  /**
   * Creates a {@code CorsProperties} with a defensive copy of the allowed origins list.
   *
   * @param allowedOrigins the allowed CORS origins
   */
  public CorsProperties {
    allowedOrigins = List.copyOf(allowedOrigins);
  }
}
