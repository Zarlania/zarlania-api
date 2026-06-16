package com.zarlania.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void getRequestIncludesCorsAllowOriginHeader() throws Exception {
    mockMvc
        .perform(get("/").header("Origin", "https://app.zarlania.com"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }

  @Test
  void preflightRequestIsAllowedForGet() throws Exception {
    mockMvc
        .perform(
            options("/")
                .header("Origin", "https://app.zarlania.com")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }
}
