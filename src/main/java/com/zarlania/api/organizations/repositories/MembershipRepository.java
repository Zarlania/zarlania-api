package com.zarlania.api.organizations.repositories;

import com.zarlania.api.organizations.entities.Membership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
  List<Membership> findByUserId(UUID userId);

  boolean existsByUserIdAndOrganizationId(UUID userId, UUID organizationId);

  void deleteByUserId(UUID userId);
}
