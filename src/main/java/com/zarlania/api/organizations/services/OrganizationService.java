package com.zarlania.api.organizations.services;

import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.dtos.OrganizationType;
import com.zarlania.api.organizations.entities.MembershipEntity;
import com.zarlania.api.organizations.entities.OrganizationEntity;
import com.zarlania.api.organizations.repositories.MembershipRepository;
import com.zarlania.api.organizations.repositories.OrganizationRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The organizations domain's whole surface to the rest of the application.
 *
 * <p>Everything here returns {@link Organization}, never an entity, so no caller in another domain
 * can traverse {@code MembershipEntity}'s lazy relation or write a row behind this service.
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizationRepository;
  private final MembershipRepository membershipRepository;

  /**
   * Creates an account's own organization and the owning membership that goes with it, as one
   * transaction — an organization with no owner would be unreachable.
   *
   * @param name unique across all organizations, case-insensitively; the username, in practice
   */
  @Transactional
  public Organization createPersonalOrganization(UUID ownerUserId, String name) {
    OrganizationEntity organization =
        organizationRepository.save(new OrganizationEntity(name, OrganizationType.PERSONAL));
    membershipRepository.save(new MembershipEntity(organization, ownerUserId, true));
    return toOrganization(organization);
  }

  /**
   * Finds the organization an account owns for itself, which is the one a session is scoped to.
   *
   * <p>{@code readOnly} so the lazy relation on {@code MembershipEntity} can be traversed inside
   * this transaction; {@code open-in-view} is false, so that traversal would fail outside one.
   */
  @Transactional(readOnly = true)
  public Optional<Organization> personalOrganizationOf(UUID userId) {
    return membershipRepository.findByUserId(userId).stream()
        .map(MembershipEntity::getOrganization)
        .filter(organization -> organization.getType() == OrganizationType.PERSONAL)
        .findFirst()
        .map(this::toOrganization);
  }

  /** Finds an organization by id, or empty if no such row exists. */
  @Transactional(readOnly = true)
  public Optional<Organization> findById(UUID id) {
    return organizationRepository.findById(id).map(this::toOrganization);
  }

  /** Whether an account belongs to an organization at all, ownership aside. */
  @Transactional(readOnly = true)
  public boolean isMember(UUID userId, UUID organizationId) {
    return membershipRepository.existsByUserIdAndOrganizationId(userId, organizationId);
  }

  /**
   * Clears every membership an account holds, and deletes the personal organization it owns.
   *
   * <p>Deleting an organization is this domain's job, not the caller's — hence a dedicated method
   * rather than a caller reaching for the repository itself.
   *
   * <p>The type and owner checks matter: the account may also belong, without owning it, to a
   * {@code GENERAL} organization, and deleting that would destroy a space other members still use.
   * So the organization delete stays gated on owner-and-personal.
   *
   * <p>Clearing the membership rows is deliberately <em>not</em> gated the same way. {@code
   * organization_memberships.user_id} is a NOT NULL foreign key to {@code users}, so the caller's
   * later account delete fails on any leftover row whichever organization it points at — an account
   * with no owned personal organization but a stray membership row would otherwise become
   * permanently unpurgeable.
   */
  @Transactional
  public void deletePersonalOrganizationOf(UUID userId) {
    Optional<OrganizationEntity> ownedPersonalOrganization =
        membershipRepository.findByUserId(userId).stream()
            .filter(MembershipEntity::isOwner)
            .map(MembershipEntity::getOrganization)
            .filter(organization -> organization.getType() == OrganizationType.PERSONAL)
            .findFirst();
    membershipRepository.deleteByUserId(userId);
    ownedPersonalOrganization.ifPresent(
        organization -> organizationRepository.deleteById(organization.getId()));
  }

  private Organization toOrganization(OrganizationEntity organization) {
    return new Organization(organization.getId(), organization.getName(), organization.getType());
  }
}
