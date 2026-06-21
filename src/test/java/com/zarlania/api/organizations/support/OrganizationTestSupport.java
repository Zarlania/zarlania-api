package com.zarlania.api.organizations.support;

import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Test support for the {@code organizations} domain. Seeds {@code users} rows directly via native
 * SQL so the {@code memberships.user_id} foreign key is satisfied, keeping the organizations tests
 * free of any compile-time dependency on the {@code users} domain.
 */
public final class OrganizationTestSupport {

  private OrganizationTestSupport() {}

  /**
   * Inserts a {@code users} row with a freshly generated id and the given email.
   *
   * @param entityManager the JPA entity manager bound to the test's transaction
   * @param email the unique email for the seeded user
   * @return the generated id of the seeded user
   */
  public static UUID seedUser(EntityManager entityManager, String email) {
    UUID id = UUID.randomUUID();
    entityManager
        .createNativeQuery(
            "INSERT INTO users (id, email, username, created_at, updated_at) "
                + "VALUES (?1, ?2, ?3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
        .setParameter(1, id)
        .setParameter(2, email)
        .setParameter(3, "seed-" + email)
        .executeUpdate();
    return id;
  }
}
