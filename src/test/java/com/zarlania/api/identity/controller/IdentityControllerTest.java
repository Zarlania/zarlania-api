package com.zarlania.api.identity.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractIntegrationTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// @SpringBootTest and H2 pin are inherited from AbstractIntegrationTest.
class IdentityControllerTest extends AbstractIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
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

    mockMvc
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
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("ok@example.com", "  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.username").exists());
  }

  @Test
  void createAccountReturns400ForMalformedEmail() throws Exception {
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email", "ok")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
  }

  @Test
  void createAccountReturns409ForDuplicateEmail() throws Exception {
    String email = unique("dupe") + "@example.com";
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, unique("n"))))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, unique("n"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("An account with this email already exists"));
  }

  @Test
  void createAccountReturns409ForDuplicateUsername() throws Exception {
    String username = unique("dupn");
    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("This username is already taken"));
  }

  @Test
  void createAccountReturns409WhenUsernameCollidesWithExistingOrgName() throws Exception {
    String collidingName = unique("org");
    User owner = userService.create(unique("o") + "@example.com", unique("o"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    mockMvc
        .perform(
            post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(unique("v") + "@example.com", collidingName)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("The requested username is unavailable"));
  }
}
