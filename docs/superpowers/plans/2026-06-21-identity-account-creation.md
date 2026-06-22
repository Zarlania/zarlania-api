# Identity Account-Creation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `identity` domain exposing `POST /accounts`, which atomically creates a user and their personal organization (named after the username).

**Architecture:** A new `com.zarlania.api.identity` domain whose `IdentityService` orchestrates the existing `UserService` and `OrganizationService` in a single transaction, fronted by `IdentityController`. A new shared `com.zarlania.api.web.ApiExceptionHandler` (the codebase's first `@RestControllerAdvice`) maps domain exceptions and bean-validation failures to RFC 7807 `ProblemDetail` responses. Cross-domain interaction is in-process bean calls exchanging only DTOs (ADR-0011).

**Tech Stack:** Java 25, Spring Boot 4.1.x, Maven (`./mvnw`), Spring Data JPA + H2/Flyway, Jakarta Bean Validation, JUnit 5 + AssertJ + MockMvc.

## Global Constraints

- Java 25 / Spring Boot 4.1.x / Maven wrapper `./mvnw` (ADR-0006).
- ADR-0011: the `identity` domain injects `UserService` and `OrganizationService` as Spring beans, exchanges only DTOs, and never imports another domain's JPA entity. The shared handler may import other domains' **exceptions** (not entities).
- Build gates must pass `./mvnw verify`: Spotless (google-java-format, 2-space indent), Checkstyle, SpotBugs/FindSecBugs, and the ≥ 80% JaCoCo coverage floor. Do not silence gates.
- Tests prove observable behavior through the public surface (HTTP + service), not mock interactions (CLAUDE.md, TDD).
- Endpoint is `POST /accounts`, create-only. Accounts are created active; no auth, passwords, login, verification, or rate limiting in this slice.
- The personal organization is named after the user's `username` (reference doc 000001).
- No secrets in any commit.
- Work is on branch `feat/47-identity-account-creation` (already created); PR references issue **#47**; release bump is **minor** (`0.4.1 → 0.5.0`) with the `release:minor` label.

---

### Task 1: Identity orchestration service (`IdentityService` + `Account` DTO)

Creates the domain's core: an `Account` DTO and an `IdentityService` that orchestrates user + personal-org creation in one transaction. Verified by a `@SpringBootTest` integration test exercising the happy path, duplicate conflicts, and transactional rollback (atomicity).

**Files:**
- Create: `src/main/java/com/zarlania/api/identity/dto/Account.java`
- Create: `src/main/java/com/zarlania/api/identity/service/IdentityService.java`
- Test: `src/test/java/com/zarlania/api/identity/service/IdentityServiceTest.java`

**Interfaces:**
- Consumes (existing): `UserService.create(String email, String username) -> com.zarlania.api.users.dto.User`; `OrganizationService.createPersonalOrganization(UUID ownerUserId, String name) -> com.zarlania.api.organizations.dto.Organization`; `OrganizationService.createGeneralOrganization(UUID, String)`; `OrganizationService.findMemberships(UUID) -> List<Membership>`; `UserService.findByEmail(String) -> Optional<User>`.
- Produces: `Account(User user, Organization personalOrganization)` and `IdentityService.createAccount(String email, String username) -> Account`.

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/zarlania/api/identity/service/IdentityServiceTest.java`:

```java
package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.exception.EmailAlreadyExistsException;
import com.zarlania.api.users.exception.UsernameAlreadyExistsException;
import com.zarlania.api.users.service.UserService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// Full context so createAccount runs in its OWN transaction (real commit/rollback), which the
// atomicity test depends on. Pin H2 so a SPRING_DATASOURCE_URL in the environment can't bleed in.
@SpringBootTest
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class IdentityServiceTest {

  @Autowired private IdentityService identityService;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  // The in-memory DB is shared across tests in this context, and emails/usernames/org-names are
  // globally unique, so each test uses fresh values.
  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void createAccountCreatesUserAndPersonalOrgNamedAfterUsername() {
    String username = unique("user");
    String email = username + "@example.com";

    Account account = identityService.createAccount(email, username);

    assertThat(account.user().id()).isNotNull();
    assertThat(account.user().email()).isEqualTo(email);
    assertThat(account.user().username()).isEqualTo(username);
    assertThat(account.personalOrganization().name()).isEqualTo(username);
    assertThat(account.personalOrganization().type()).isEqualTo(OrganizationType.PERSONAL);

    List<Membership> memberships =
        organizationService.findMemberships(account.personalOrganization().id());
    assertThat(memberships)
        .singleElement()
        .satisfies(
            membership -> {
              assertThat(membership.userId()).isEqualTo(account.user().id());
              assertThat(membership.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void createAccountRejectsDuplicateEmail() {
    String email = unique("dupemail") + "@example.com";
    identityService.createAccount(email, unique("name"));

    assertThatThrownBy(() -> identityService.createAccount(email, unique("name")))
        .isInstanceOf(EmailAlreadyExistsException.class);
  }

  @Test
  void createAccountRejectsDuplicateUsername() {
    String username = unique("dupname");
    identityService.createAccount(unique("e") + "@example.com", username);

    assertThatThrownBy(() -> identityService.createAccount(unique("e") + "@example.com", username))
        .isInstanceOf(UsernameAlreadyExistsException.class);
  }

  @Test
  void createAccountRollsBackUserWhenPersonalOrgNameCollides() {
    // Arrange: a general org whose name will collide with the next account's username.
    String collidingName = unique("collide");
    User owner = userService.create(unique("owner") + "@example.com", unique("owner"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    String victimEmail = unique("victim") + "@example.com";

    assertThatThrownBy(() -> identityService.createAccount(victimEmail, collidingName))
        .isInstanceOf(OrganizationNameAlreadyExistsException.class);

    // The user insert must have rolled back together with the failed org creation.
    assertThat(userService.findByEmail(victimEmail)).isEmpty();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=IdentityServiceTest`
Expected: FAIL — compilation error, `Account` and `IdentityService` do not exist.

- [ ] **Step 3: Create the `Account` DTO**

Create `src/main/java/com/zarlania/api/identity/dto/Account.java`:

```java
package com.zarlania.api.identity.dto;

import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.users.dto.User;

/**
 * The result of creating an account: the new user together with their personal organization. Carries
 * the canonical domain name and composes the {@code users} and {@code organizations} domains' DTOs;
 * no entities cross the boundary (ADR-0011).
 *
 * @param user the created user
 * @param personalOrganization the user's personal organization, named after the username
 */
public record Account(User user, Organization personalOrganization) {}
```

- [ ] **Step 4: Create the `IdentityService`**

Create `src/main/java/com/zarlania/api/identity/service/IdentityService.java`:

```java
package com.zarlania.api.identity.service;

import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates account creation across the {@code users} and {@code organizations} domains. The
 * public surface of the {@code identity} domain. Injects each domain's service as a Spring bean and
 * exchanges only DTOs (ADR-0011).
 */
@Service
@RequiredArgsConstructor
public class IdentityService {

  private final UserService userService;
  private final OrganizationService organizationService;

  /**
   * Creates an account — a user and their personal organization, named after the username — in a
   * single transaction. Because both delegated services join this transaction, a failure creating
   * the organization rolls back the user creation too, so no orphaned user remains.
   *
   * @param email the new user's email
   * @param username the new user's unique public handle
   * @return the created account (user + personal organization)
   */
  @Transactional
  public Account createAccount(String email, String username) {
    User user = userService.create(email, username);
    Organization personalOrganization =
        organizationService.createPersonalOrganization(user.id(), user.username());
    return new Account(user, personalOrganization);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=IdentityServiceTest`
Expected: PASS — 4 tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/zarlania/api/identity src/test/java/com/zarlania/api/identity
git commit -m "$(cat <<'EOF'
feat: add identity account-creation orchestration (#47)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: HTTP endpoint with input validation (`IdentityController` + `CreateAccountRequest` + validation handler)

Exposes `POST /accounts` returning `201` with the `Account` body, plus Jakarta Bean Validation at the HTTP edge mapped to `400` ProblemDetail with per-field detail. Adds `spring-boot-starter-validation` and enables RFC 7807 problem responses.

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-validation`)
- Modify: `src/main/resources/application.properties` (enable problemdetails)
- Create: `src/main/java/com/zarlania/api/identity/dto/CreateAccountRequest.java`
- Create: `src/main/java/com/zarlania/api/identity/controller/IdentityController.java`
- Create: `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`
- Test: `src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java`

**Interfaces:**
- Consumes: `IdentityService.createAccount(String, String) -> Account` (Task 1).
- Produces: HTTP `POST /accounts`; `CreateAccountRequest(String email, String username)`; `ApiExceptionHandler.handleValidation(MethodArgumentNotValidException) -> ProblemDetail` (extended in Task 3).

- [ ] **Step 1: Add the validation dependency**

In `pom.xml`, add inside `<dependencies>` (next to the other `spring-boot-starter-*` entries):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

- [ ] **Step 2: Write the failing controller test**

Create `src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java`:

```java
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
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(email, username)))
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
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body("ok@example.com", "  ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.username").exists());
  }

  @Test
  void createAccountReturns400ForMalformedEmail() throws Exception {
    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body("not-an-email", "ok")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.email").exists());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=IdentityControllerTest`
Expected: FAIL — compilation error, `CreateAccountRequest`, `IdentityController`, and `ApiExceptionHandler` do not exist.

- [ ] **Step 4: Create the request DTO with validation annotations**

Create `src/main/java/com/zarlania/api/identity/dto/CreateAccountRequest.java`:

```java
package com.zarlania.api.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /accounts}, validated at the HTTP boundary. The size limits mirror
 * the {@code users.email} (320) and {@code users.username} (100) columns; these bounds also exist as
 * domain invariants in {@code UserService} (defense in depth at two boundaries — the only accepted
 * duplication, since exposing the {@code users} constants here would breach the domain boundary).
 *
 * @param email the new user's email
 * @param username the new user's unique public handle
 */
public record CreateAccountRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 100) String username) {}
```

- [ ] **Step 5: Create the controller**

Create `src/main/java/com/zarlania/api/identity/controller/IdentityController.java`:

```java
package com.zarlania.api.identity.controller;

import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.identity.dto.CreateAccountRequest;
import com.zarlania.api.identity.service.IdentityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for the {@code identity} domain. */
@RestController
@RequiredArgsConstructor
public class IdentityController {

  private final IdentityService identityService;

  /**
   * Creates an account: a user and their personal organization.
   *
   * @param request the validated account-creation payload
   * @return {@code 201 Created} with the created {@link Account}
   */
  @PostMapping("/accounts")
  public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
    Account account = identityService.createAccount(request.email(), request.username());
    return ResponseEntity.status(HttpStatus.CREATED).body(account);
  }
}
```

- [ ] **Step 6: Create the exception handler (validation mapping only)**

Create `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`:

```java
package com.zarlania.api.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions across all domains to RFC 7807 {@link ProblemDetail} responses. The codebase's
 * first global web exception handler; it lives in the shared {@code web} package because it serves
 * every domain's controllers. Importing another domain's exception types is permitted under
 * ADR-0011 (that rule forbids importing entities, not exceptions).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** Bean-validation failures at the HTTP edge: 400 with a per-field {@code errors} map. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setDetail("Request validation failed");
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    problem.setProperty("errors", errors);
    return problem;
  }
}
```

- [ ] **Step 7: Enable RFC 7807 problem responses**

In `src/main/resources/application.properties`, add under the OpenAPI section:

```properties
# Render framework error responses as RFC 7807 application/problem+json.
spring.mvc.problemdetails.enabled=true
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `./mvnw test -Dtest=IdentityControllerTest`
Expected: PASS — 3 tests green.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/main/java/com/zarlania/api/identity/dto/CreateAccountRequest.java src/main/java/com/zarlania/api/identity/controller src/main/java/com/zarlania/api/web src/test/java/com/zarlania/api/identity/controller
git commit -m "$(cat <<'EOF'
feat: expose POST /accounts with request validation (#47)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Domain-exception → HTTP mapping (`409` conflicts + defensive `400`)

Extends `ApiExceptionHandler` so the duplicate/collision domain exceptions return `409` and any service-layer `IllegalArgumentException` returns `400`. Verified end-to-end through the controller for the realistic `409` paths, plus a direct handler unit test for the defensive `IllegalArgumentException` mapping (which bean validation otherwise intercepts).

**Files:**
- Modify: `src/main/java/com/zarlania/api/web/ApiExceptionHandler.java`
- Modify: `src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java`
- Test: `src/test/java/com/zarlania/api/web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `EmailAlreadyExistsException`, `UsernameAlreadyExistsException` (users), `OrganizationNameAlreadyExistsException`, `PersonalOrganizationAlreadyExistsException` (organizations); `UserService`/`OrganizationService` for test arrangement.
- Produces: `ApiExceptionHandler.handleIllegalArgument(IllegalArgumentException) -> ProblemDetail` and the conflict handlers.

- [ ] **Step 1: Add the failing controller error-path tests**

Append these tests inside `IdentityControllerTest` (and add the imports/fields shown):

```java
// Add to the imports:
// import com.zarlania.api.organizations.service.OrganizationService;
// import com.zarlania.api.users.dto.User;
// import com.zarlania.api.users.service.UserService;

// Add as fields:
// @Autowired private UserService userService;
// @Autowired private OrganizationService organizationService;

  @Test
  void createAccountReturns409ForDuplicateEmail() throws Exception {
    String email = unique("dupe") + "@example.com";
    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(email, unique("n"))))
        .andExpect(status().isCreated());

    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(email, unique("n"))))
        .andExpect(status().isConflict());
  }

  @Test
  void createAccountReturns409ForDuplicateUsername() throws Exception {
    String username = unique("dupn");
    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isCreated());

    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(unique("e") + "@example.com", username)))
        .andExpect(status().isConflict());
  }

  @Test
  void createAccountReturns409WhenUsernameCollidesWithExistingOrgName() throws Exception {
    String collidingName = unique("org");
    com.zarlania.api.users.dto.User owner =
        userService.create(unique("o") + "@example.com", unique("o"));
    organizationService.createGeneralOrganization(owner.id(), collidingName);

    mockMvc()
        .perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body(unique("v") + "@example.com", collidingName)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("The requested username is unavailable"));
  }
```

(Replace the fully-qualified `User` reference with a top-level import if preferred; the field declarations `@Autowired private UserService userService;` and `@Autowired private OrganizationService organizationService;` must be added to the class.)

- [ ] **Step 2: Write the failing handler unit test**

Create `src/test/java/com/zarlania/api/web/ApiExceptionHandlerTest.java`:

```java
package com.zarlania.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsIllegalArgumentToBadRequest() {
    ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException("email must not be blank"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getDetail()).isEqualTo("email must not be blank");
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=IdentityControllerTest,ApiExceptionHandlerTest`
Expected: FAIL — `handleIllegalArgument` does not exist (compile error) and the new `409` controller tests fail (the conflict exceptions currently fall through to `500`).

- [ ] **Step 4: Extend the exception handler**

Add these handler methods to `ApiExceptionHandler` (and the imports below):

```java
// Add to the imports:
// import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
// import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
// import com.zarlania.api.users.exception.EmailAlreadyExistsException;
// import com.zarlania.api.users.exception.UsernameAlreadyExistsException;

  /** Service-layer input rejections that bypass bean validation (defensive): 400. */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  /** Email already registered: 409. Detail is fixed so the attempted value is not echoed back. */
  @ExceptionHandler(EmailAlreadyExistsException.class)
  ProblemDetail handleEmailConflict(EmailAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "An account with this email already exists");
  }

  /** Username already taken: 409. */
  @ExceptionHandler(UsernameAlreadyExistsException.class)
  ProblemDetail handleUsernameConflict(UsernameAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This username is already taken");
  }

  /**
   * The chosen username collides with an existing organization's globally-unique name, so the
   * personal org cannot be created: surfaced to the caller as an unavailable username (409).
   */
  @ExceptionHandler(OrganizationNameAlreadyExistsException.class)
  ProblemDetail handleUsernameUnavailable(OrganizationNameAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "The requested username is unavailable");
  }

  /** Defensive: cannot occur for a brand-new user, but mapped to 409 rather than 500. */
  @ExceptionHandler(PersonalOrganizationAlreadyExistsException.class)
  ProblemDetail handlePersonalOrgConflict(PersonalOrganizationAlreadyExistsException ex) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT, "The user already owns a personal organization");
  }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=IdentityControllerTest,ApiExceptionHandlerTest`
Expected: PASS — all controller tests (incl. the three `409` paths) and the handler unit test green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/zarlania/api/web/ApiExceptionHandler.java src/test/java/com/zarlania/api/identity/controller/IdentityControllerTest.java src/test/java/com/zarlania/api/web/ApiExceptionHandlerTest.java
git commit -m "$(cat <<'EOF'
feat: map identity domain exceptions to HTTP responses (#47)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Documentation sync (reference doc 000001 + CLAUDE.md)

Updates living documentation to match the new behavior, per the spec's Documentation impact. Reference docs describe behavior — **not** API endpoints (OpenAPI owns those). Verified with `./scripts/ref check`.

**Files:**
- Modify: `docs/reference/000001-user-organization-association-rules.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update reference doc 000001**

Use `./scripts/ref show 000001` to read it, then edit it so it reflects the now-implemented orchestration:
- Personal organizations: change "**at most one**" to "**exactly one** personal org per user", and drop the wording attributing the second half to a "future account-creation orchestration" — the `identity` domain now creates the personal org on account creation.
- Change the personal-org naming from "intended to be named after the owner's `username` (via the future account-creation orchestration)" to a present-tense statement that it **is** named after the username by the `identity` domain.
- In the Scope section, revise the out-of-scope note: account-creation orchestration that atomically creates a user and their personal org now **exists** in `com.zarlania.api.identity`; cross-domain existence checks, permission gates, and authentication/secrets remain deferred.
- Add `com.zarlania.api.identity` to the doc's `Related` list (front-matter `related:` and the Related section).

- [ ] **Step 2: Validate the reference doc**

Run: `./scripts/ref check`
Expected: passes with no errors (metadata table and front matter consistent; `updated` date refreshed if the tool requires it).

- [ ] **Step 3: Update CLAUDE.md (Working with reference docs)**

In the `## Working with reference docs` section, add guidance (prose, matching the surrounding style) stating two things:
- When a change alters documented behavior, update the relevant reference doc (or author a new one) **as part of that change** — reference docs are living and must not drift from the code.
- Reference docs document behavior and rules, **not** API endpoint contracts; the public springdoc OpenAPI (`/v3/api-docs`, ADR-0003) is the source of truth for endpoints.

- [ ] **Step 4: Commit**

```bash
git add docs/reference/000001-user-organization-association-rules.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: sync user-org reference doc and reference-doc policy (#47)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Version bump and full verification

Bumps the release version per the in-PR SemVer rule and runs the full gated build to confirm coverage, style, and security gates pass before opening the PR.

**Files:**
- Modify: `pom.xml` (`<version>`)

- [ ] **Step 1: Bump the version (minor)**

Run: `./scripts/bump-version bump minor`
Expected: `pom.xml` `<version>` changes from `0.4.1` to `0.5.0`.

- [ ] **Step 2: Run the full gated build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — all tests pass; Spotless, Checkstyle, SpotBugs/FindSecBugs clean; JaCoCo coverage ≥ 80%.

If JaCoCo flags the new `identity`/`web` classes below threshold, add the missing behavior test (do not lower the threshold or add suppressions) and re-run.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "$(cat <<'EOF'
chore: bump version to 0.5.0 for identity account creation (#47)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin feat/47-identity-account-creation
```

Open a PR titled to reference `#47`, apply the `release:minor` label (so the "Release version bump" check passes against `0.5.0`), and confirm CI is green. Use the `superpowers:finishing-a-development-branch` skill to drive the merge.

---

## Notes on test isolation

The `@SpringBootTest` classes share one in-memory H2 instance (Flyway-migrated, `DB_CLOSE_DELAY=-1`) across the cached Spring context, and email/username/org-name are globally unique. Every test therefore generates fresh values via `unique(...)`; the atomicity test deliberately avoids a test-managed transaction so `createAccount` commits/rolls back for real.
