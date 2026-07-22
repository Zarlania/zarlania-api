package com.zarlania.api.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

  @GetMapping("/hello")
  public HelloResponse hello() {
    return new HelloResponse("Hello from Zarlania!");
  }

  public record HelloResponse(String message) {}
}
