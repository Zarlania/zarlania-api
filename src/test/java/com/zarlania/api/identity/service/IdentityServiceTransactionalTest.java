package com.zarlania.api.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.support.AbstractTransactionalTest;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Runs with NO wrapping test transaction so createAccount executes in its own transaction and
// commits-or-rolls-back for real — the only way to observe that a failed personal-org step actually
// undid the user insert. A wrapping test transaction would make the inner @Transactional merely
// join
// it, so the "user is gone" post-condition would still see the row. Because it therefore commits,
// it's in the serial *TransactionalTest suite and relies on truncation (from
// AbstractTransactionalTest)
// rather than rollback for isolation.
class IdentityServiceTransactionalTest extends AbstractTransactionalTest {

  @Autowired private IdentityService identityService;
  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;

  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void createAccountRollsBackUserWhenPersonalOrgNameCollides() {
    // Arrange: a general org whose name will collide with the next account's username. This must be
    // committed so createAccount's own transaction can see it.
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
