package com.zarlania.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

  @GetMapping("/")
  public Map<String, String> hello() {
    return Map.of("message", "Hello from Zarlania API v2");
  }
}
