package com.zarlania.api.organizations.repositories;

import com.zarlania.api.organizations.entities.Membership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link Membership}, keyed on the member rather than the organization. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

  /** Every organization one account belongs to, owned or not. Empty for an account with none. */
  List<Membership> findByUserId(UUID userId);

  /** Whether an account belongs to an organization at all, ownership aside. */
  boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

  /**
   * Clears every membership one account holds. Purging an account has to run this before the user
   * row can go, since {@code organization_memberships} carries a real foreign key to it.
   */
  void deleteByUserId(UUID userId);
}
