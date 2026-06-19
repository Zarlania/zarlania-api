package com.zarlania.api.organizations.entity;

import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An organization: the ownership root for data in the system. Internal to the {@code organizations}
 * domain; crosses boundaries via the {@link com.zarlania.api.organizations.dto.Organization} DTO.
 */
@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor
public class OrganizationEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "name", nullable = false, length = 200)
  private String name;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false, length = 20)
  private OrganizationType type;
}
