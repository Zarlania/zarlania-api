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

@Service
@RequiredArgsConstructor
public class OrganizationService {

  private final OrganizationRepository organizations;
  private final MembershipRepository memberships;

  @Transactional
  public OrganizationDto createPersonalOrganization(UUID ownerUserId, String name) {
    Organization org = organizations.save(new Organization(name, OrganizationType.PERSONAL));
    memberships.save(new Membership(org, ownerUserId, true));
    return toDto(org);
  }

  // readOnly so Membership::getOrganization can traverse its LAZY relation inside this
  // transaction; open-in-view is false, so that traversal would fail outside one.
  @Transactional(readOnly = true)
  public Optional<OrganizationDto> personalOrganizationOf(UUID userId) {
    return memberships.findByUserId(userId).stream()
        .map(Membership::getOrganization)
        .filter(org -> org.getType() == OrganizationType.PERSONAL)
        .findFirst()
        .map(this::toDto);
  }

  @Transactional(readOnly = true)
  public Optional<OrganizationDto> findById(UUID id) {
    return organizations.findById(id).map(this::toDto);
  }

  @Transactional(readOnly = true)
  public boolean isMember(UUID userId, UUID organizationId) {
    return memberships.existsByUserIdAndOrganizationId(userId, organizationId);
  }

  // Deleting an organization is this domain's job, not the caller's (UnverifiedAccountCleanup's,
  // today) — hence a dedicated method rather than the caller reaching for OrganizationRepository
  // itself. The type and owner checks matter: userId may also belong (non-owning) to a GENERAL
  // organization, and deleting that would destroy a space other members still use, so the
  // organization delete stays gated on owner-and-PERSONAL. Clearing every membership row is not
  // gated the same way: organization_memberships.user_id is a NOT NULL FK to users, so the
  // caller's later users.deleteById(userId) fails on any leftover row regardless of which org it
  // points at — a user with, say, no owning personal org but a stray membership row must still
  // have that row cleared or the account becomes permanently unpurgeable.
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
