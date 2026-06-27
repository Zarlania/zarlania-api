package com.zarlania.api.identity.controller;

import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractEndToEndTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

// End-to-end over real HTTP: asserts the request/response contract and middleware (validation, the
// global exception handler) for POST /accounts. Whether rows actually persisted is covered by the
// service-layer integration tests, not here.
class IdentityControllerEndToEndTest extends AbstractEndToEndTest {

  // Used only to arrange a precondition (an existing general org) that has no public endpoint.
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  private static String body(String email, String username) {
    return "{\"email\":\"" + email + "\",\"username\":\"" + username + "\"}";
  }

  private RestTestClient.ResponseSpec postAccount(String email, String username) {
    return restClient
        .post()
        .uri("/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body(email, username))
        .exchange();
  }

  @Test
  void createAccountReturns201WithUserAndPersonalOrg() {
    String username = unique("u");
    String email = username + "@example.com";

    postAccount(email, username)
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.user.id")
        .exists()
        .jsonPath("$.user.email")
        .isEqualTo(email)
        .jsonPath("$.user.username")
        .isEqualTo(username)
        .jsonPath("$.personalOrganization.name")
        .isEqualTo(username)
        .jsonPath("$.personalOrganization.type")
        .isEqualTo("PERSONAL");
  }

  @Test
  void createAccountReturns400ForBlankUsername() {
    postAccount("ok@example.com", "  ")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.errors.username")
        .exists();
  }

  @Test
  void createAccountReturns400ForMalformedEmail() {
    postAccount("not-an-email", "ok")
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.errors.email")
        .exists();
  }

  @Test
  void createAccountReturns409ForDuplicateEmail() {
    String email = unique("dupe") + "@example.com";
    postAccount(email, unique("n")).expectStatus().isCreated();

    postAccount(email, unique("n"))
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo("An account with this email already exists");
  }

  @Test
  void createAccountReturns409ForDuplicateUsername() {
    String username = unique("dupn");
    postAccount(unique("e") + "@example.com", username).expectStatus().isCreated();

    postAccount(unique("e") + "@example.com", username)
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo("This username is already taken");
  }

  @Test
  void createAccountReturns409WhenUsernameCollidesWithExistingOrgName() {
    String collidingName = unique("org");
    User owner = userService.create(unique("o") + "@example.com", unique("o"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    postAccount(unique("v") + "@example.com", collidingName)
        .expectStatus()
        .isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.detail")
        .isEqualTo("The requested username is unavailable");
  }
}
