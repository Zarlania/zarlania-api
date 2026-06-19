package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrganizationMapperTest {

  private final OrganizationMapper mapper = new OrganizationMapper();

  @Test
  void mapsOrganizationEntityToDto() {
    UUID expectedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    OrganizationEntity entity = new OrganizationEntity();
    // id has no setter (immutable, DB-generated); set it directly for this mapping unit test.
    ReflectionTestUtils.setField(entity, "id", expectedId);
    entity.setName("Acme");
    entity.setType(OrganizationType.GENERAL);

    Organization dto = mapper.toDto(entity);

    assertThat(dto.id()).isEqualTo(expectedId);
    assertThat(dto.name()).isEqualTo("Acme");
    assertThat(dto.type()).isEqualTo(OrganizationType.GENERAL);
  }

  @Test
  void mapsMembershipEntityToDtoUsingOrganizationId() {
    UUID expectedOrgId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    OrganizationEntity org = new OrganizationEntity();
    // id has no setter (immutable, DB-generated); set it directly for this mapping unit test.
    ReflectionTestUtils.setField(org, "id", expectedOrgId);
    org.setName("Acme");
    org.setType(OrganizationType.GENERAL);

    MembershipEntity entity = new MembershipEntity();
    entity.setOrganization(org);
    entity.setUserId(UUID.randomUUID());
    entity.setRole(MembershipRole.OWNER);

    Membership dto = mapper.toDto(entity);

    assertThat(dto.organizationId()).isEqualTo(expectedOrgId);
    assertThat(dto.userId()).isEqualTo(entity.getUserId());
    assertThat(dto.role()).isEqualTo(MembershipRole.OWNER);
  }
}
