package com.zarlania.api.organizations.repository;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.entity.MembershipEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link MembershipEntity}. Internal to the {@code organizations} domain.
 */
public interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> {

  /**
   * Lists all memberships of an organization.
   *
   * @param organizationId the organization id
   * @return the organization's memberships (empty if none)
   */
  List<MembershipEntity> findByOrganization_Id(UUID organizationId);

  /**
   * Reports whether the given user already has a membership in the given organization.
   *
   * @param organizationId the organization id
   * @param userId the user id
   * @return {@code true} if a membership already exists for that user in that organization
   */
  boolean existsByOrganization_IdAndUserId(UUID organizationId, UUID userId);

  /**
   * Finds the given user's membership in the given organization, if any.
   *
   * @param organizationId the organization id
   * @param userId the user id
   * @return the membership, if one exists
   */
  Optional<MembershipEntity> findByOrganization_IdAndUserId(UUID organizationId, UUID userId);

  /**
   * Reports whether the user holds the given role in an organization of the given type — used to
   * enforce the "one personal organization per user" rule.
   *
   * @param userId the user id
   * @param role the membership role to match
   * @param type the organization type to match
   * @return {@code true} if such a membership exists
   */
  boolean existsByUserIdAndRoleAndOrganization_Type(
      UUID userId, MembershipRole role, OrganizationType type);
}
