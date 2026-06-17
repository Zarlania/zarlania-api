package com.zarlania.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "zarlania.cors.allowed-origins=https://zarlania.com")
class CorsConfigTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Test
  void allowedOriginGetsCorsHeader() throws Exception {
    mockMvc()
        .perform(get("/").header("Origin", "https://zarlania.com"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }

  @Test
  void disallowedOriginIsRejected() throws Exception {
    mockMvc()
        .perform(
            options("/")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  void allowedOriginPreflightSucceeds() throws Exception {
    mockMvc()
        .perform(
            options("/")
                .header("Origin", "https://zarlania.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://zarlania.com"));
  }
}
