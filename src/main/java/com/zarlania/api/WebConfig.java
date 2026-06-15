package com.zarlania.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permissive CORS for the POC: allows any origin to make GET requests so the Angular frontend (e.g.
 * https://app.zarlania.com) can call this API from the browser. Tighten allowedOrigins to the real
 * frontend origin(s) for production.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**").allowedOrigins("*").allowedMethods("GET", "OPTIONS");
  }
}
