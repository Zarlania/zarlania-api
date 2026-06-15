package com.zarlania.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for the Zarlania API root endpoint. */
@RestController
public class HelloController {

  /** Returns a hello message from the API. */
  @GetMapping("/")
  public Map<String, String> hello() {
    return Map.of("message", "Hello from Zarlania API v2");
  }
}
