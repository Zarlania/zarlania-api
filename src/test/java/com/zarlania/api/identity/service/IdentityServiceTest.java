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
