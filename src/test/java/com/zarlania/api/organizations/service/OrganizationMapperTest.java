package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import org.junit.jupiter.api.Test;

class OrganizationMapperTest {

  private final OrganizationMapper mapper = new OrganizationMapper();

  @Test
  void mapsOrganizationEntityToDto() {
    OrganizationEntity entity = new OrganizationEntity();
    entity.setName("Acme");
    entity.setType(OrganizationType.GENERAL);

    Organization dto = mapper.toDto(entity);

    assertThat(dto.id()).isEqualTo(entity.getId());
    assertThat(dto.name()).isEqualTo("Acme");
    assertThat(dto.type()).isEqualTo(OrganizationType.GENERAL);
  }

  @Test
  void mapsMembershipEntityToDtoUsingOrganizationId() {
    OrganizationEntity org = new OrganizationEntity();
    org.setName("Acme");
    org.setType(OrganizationType.GENERAL);

    MembershipEntity entity = new MembershipEntity();
    entity.setOrganization(org);
    entity.setUserId(java.util.UUID.randomUUID());
    entity.setRole(MembershipRole.OWNER);

    Membership dto = mapper.toDto(entity);

    assertThat(dto.organizationId()).isEqualTo(org.getId());
    assertThat(dto.userId()).isEqualTo(entity.getUserId());
    assertThat(dto.role()).isEqualTo(MembershipRole.OWNER);
  }
}
