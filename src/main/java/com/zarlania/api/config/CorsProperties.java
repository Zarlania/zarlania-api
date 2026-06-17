package com.zarlania.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configurable CORS allowlist. Bound from {@code zarlania.cors.*}. */
@ConfigurationProperties(prefix = "zarlania.cors")
public record CorsProperties(List<String> allowedOrigins) {

  /**
   * Creates a {@code CorsProperties}, validating the configured origins and storing a defensive,
   * immutable copy. Invalid configuration is rejected at bind time so a misconfiguration fails fast
   * rather than silently weakening CORS (see ADR-0004).
   *
   * @param allowedOrigins the allowed CORS origins; must be non-empty and contain no blank entries
   *     or the {@code "*"} wildcard
   * @throws IllegalArgumentException if the list is null/empty or contains a blank entry or
   *     wildcard
   */
  public CorsProperties {
    if (allowedOrigins == null || allowedOrigins.isEmpty()) {
      throw new IllegalArgumentException(
          "zarlania.cors.allowed-origins must contain at least one explicit origin");
    }
    for (String origin : allowedOrigins) {
      if (origin == null || origin.isBlank() || "*".equals(origin.trim())) {
        throw new IllegalArgumentException(
            "zarlania.cors.allowed-origins must not contain blank values or the wildcard '*'");
      }
    }
    allowedOrigins = List.copyOf(allowedOrigins);
  }
}
