package com.zarlania.api.identity.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class IdentityControllerTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).build();
  }

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String body(String email, String username) {
    return "{\"email\":\"" + email + "\",\"username\":\"" + username + "\"}";
  }

  @Test
  void createAccountReturns201WithUserAndPersonalOrg() throws Exception {
    String username = unique("u");
    String email = username + "@example.com";

    mockMvc()
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, username)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.id").exists())
        .andExpect(jsonPath("$.user.email").value(email))
        .andExpect(jsonPath("$.user.username").value(username))
        .andExpect(jsonPath("$.personalOrganization.name").value(username))
        .andExpect(jsonPath("$.personalOrganization.type").value("PERSONAL"));
  }

  @Test
  void createAccountReturns400ForBlankUsername() throws Exception {
    mockMvc()
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("ok@example.com", "  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.username").exists());
  }

  @Test
  void createAccountReturns400ForMalformedEmail() throws Exception {
    mockMvc()
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email", "ok")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
  }
}
