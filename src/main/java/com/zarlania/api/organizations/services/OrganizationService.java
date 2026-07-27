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

  private OrganizationDto toDto(Organization org) {
    return new OrganizationDto(org.getId(), org.getName(), org.getType());
  }
}
