package com.zarlania.api.organizations.entities;

import com.zarlania.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "organization_memberships")
public class Membership extends BaseEntity {

  // Same domain as Organization: a mapped relation is fine here. userId below stays a
  // plain FK column because users is a foreign domain (see CLAUDE.md domain boundary rules).
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "organization_id")
  private Organization organization;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "is_owner", nullable = false)
  private boolean owner;

  protected Membership() {}

  public Membership(Organization organization, UUID userId, boolean owner) {
    this.organization = organization;
    this.userId = userId;
    this.owner = owner;
  }

  public Organization getOrganization() {
    return organization;
  }

  public UUID getUserId() {
    return userId;
  }

  public boolean isOwner() {
    return owner;
  }
}
