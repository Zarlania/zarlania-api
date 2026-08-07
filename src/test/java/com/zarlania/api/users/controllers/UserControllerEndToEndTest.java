package com.zarlania.api.users.controllers;

import static com.zarlania.api.testsupport.AuthEndpoints.accessTokenOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.testsupport.FlowTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What {@code GET /users/me} answers with, and whose identity it answers about.
 *
 * <p>The route resolves everything from the bearer token and takes no input of its own, so the
 * property worth pinning is that two callers holding two tokens each see only themselves. A test
 * with one account could not tell a correct implementation from one that returns the first row it
 * finds.
 */
@SpringBootTest(
    properties = {
      "zarlania.throttle.endpoints.register.limit=1000",
      "zarlania.throttle.endpoints.verify.limit=1000",
      "zarlania.throttle.endpoints.login.limit=1000"
    })
class UserControllerEndToEndTest extends FlowTestBase {

  @Test
  void returnsTheCallersOwnIdentityAndPersonalOrganization() throws Exception {
    MvcResult login = registerVerifyAndLogin("mira@example.com", "mira");

    auth.me(accessTokenOf(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("mira@example.com"))
        .andExpect(jsonPath("$.user.username").value("mira"))
        .andExpect(jsonPath("$.user.emailVerified").value(true))
        // Registration names the personal organization after the username, so nothing downstream
        // has to invent a display name for an account that has not chosen one.
        .andExpect(jsonPath("$.organization.name").value("mira"))
        .andExpect(jsonPath("$.organization.type").value("PERSONAL"));
  }

  // Never carries proof material: a response describing an identity must not also carry the means
  // to assume it. The credentials domain exists to keep those apart, and this is where that shows.
  @Test
  void carriesNoPasswordHashAndNoTokens() throws Exception {
    MvcResult login = registerVerifyAndLogin("otis@example.com", "otis");

    auth.me(accessTokenOf(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
        .andExpect(jsonPath("$.user.password").doesNotExist())
        .andExpect(jsonPath("$..token").doesNotExist());
  }

  @Test
  void answersAboutTheTokensOwnerRatherThanWhicheverAccountIsFound() throws Exception {
    MvcResult firstLogin = registerVerifyAndLogin("pia@example.com", "pia");
    MvcResult secondLogin = registerVerifyAndLogin("quinn@example.com", "quinn");

    auth.me(accessTokenOf(firstLogin)).andExpect(jsonPath("$.user.username").value("pia"));
    auth.me(accessTokenOf(secondLogin)).andExpect(jsonPath("$.user.username").value("quinn"));
  }

  @Test
  void withoutATokenTheRouteIsNotReachableAtAll() throws Exception {
    mockMvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
  }
}
