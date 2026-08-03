package com.zarlania.api.organizations.services;

import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.entities.Membership;
import com.zarlania.api.organizations.entities.Organization;
import com.zarlania.api.organizations.entities.OrganizationType;
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
 * <p>Everything here returns {@link OrganizationDto}, never an entity, so no caller in another
 * domain can traverse {@code Membership}'s lazy relation or write a row behind this service.
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;

  /**
   * Creates an account's own organization and the owning membership that goes with it, as one
   * transaction — an organization with no owner would be unreachable.
   *
   * @param name unique across all organizations, case-insensitively; the username, in practice
   */
  @Transactional
  public OrganizationDto createPersonalOrganization(UUID ownerUserId, String name) {
    Organization org = organizations.save(new Organization(name, OrganizationType.PERSONAL));
    memberships.save(new Membership(org, ownerUserId, true));
    return toDto(org);
  }

  /**
   * Finds the organization an account owns for itself, which is the one a session is scoped to.
   *
   * <p>{@code readOnly} so the lazy relation on {@code Membership} can be traversed inside this
   * transaction; {@code open-in-view} is false, so that traversal would fail outside one.
   */
  @Transactional(readOnly = true)
  public Optional<OrganizationDto> personalOrganizationOf(UUID userId) {
    return memberships.findByUserId(userId).stream()
        .map(Membership::getOrganization)
        .filter(org -> org.getType() == OrganizationType.PERSONAL)
        .findFirst()
        .map(this::toDto);
  }

  /** Finds an organization by id, or empty if no such row exists. */
  @Transactional(readOnly = true)
  public Optional<OrganizationDto> findById(UUID id) {
    return organizations.findById(id).map(this::toDto);
  }

  /** Whether an account belongs to an organization at all, ownership aside. */
  @Transactional(readOnly = true)
  public boolean isMember(UUID userId, UUID organizationId) {
    return memberships.existsByUserIdAndOrganizationId(userId, organizationId);
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
    Optional<Organization> ownedPersonalOrg =
        memberships.findByUserId(userId).stream()
            .filter(Membership::isOwner)
            .map(Membership::getOrganization)
            .filter(org -> org.getType() == OrganizationType.PERSONAL)
            .findFirst();
    memberships.deleteByUserId(userId);
    ownedPersonalOrg.ifPresent(org -> organizations.deleteById(org.getId()));
  }

  private OrganizationDto toDto(Organization org) {
    return new OrganizationDto(org.getId(), org.getName(), org.getType());
  }
}
