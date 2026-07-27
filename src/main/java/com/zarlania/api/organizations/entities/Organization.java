package com.zarlania.api.organizations.entities;

import com.zarlania.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrganizationType type;

  protected Organization() {}

  public Organization(String name, OrganizationType type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public OrganizationType getType() {
    return type;
  }
}
