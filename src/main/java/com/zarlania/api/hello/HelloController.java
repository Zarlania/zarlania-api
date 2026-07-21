package com.zarlania.api.hello;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

  @GetMapping("/hello")
  public HelloResponse hello() {
    return new HelloResponse("Hello from Zarlania!");
  }

  public record HelloResponse(String message) {}
}
