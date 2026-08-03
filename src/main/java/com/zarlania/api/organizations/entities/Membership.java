package com.zarlania.api.organizations.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One account's place in one organization, and whether it owns that organization.
 *
 * <p>Membership rather than a column on either side, because the relationship carries its own
 * attribute ({@code owner}) and, once {@code GENERAL} organizations exist, will be many-to-many.
 */
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

  /**
   * @param organization the organization joined; an in-domain relation, unlike {@code userId}
   * @param userId a plain foreign-key id, because users is a different domain
   * @param owner whether this member owns the organization rather than merely belonging to it
   */
  public Membership(Organization organization, UUID userId, boolean owner) {
    this.organization = organization;
    this.userId = userId;
    this.owner = owner;
  }

  /** The organization joined. Lazily loaded, so touching it needs an open session. */
  public Organization getOrganization() {
    return organization;
  }

  /** The member, as a plain foreign-key id into the users domain. */
  public UUID getUserId() {
    return userId;
  }

  /** Whether this member owns the organization. A personal organization has exactly one owner. */
  public boolean isOwner() {
    return owner;
  }
}
