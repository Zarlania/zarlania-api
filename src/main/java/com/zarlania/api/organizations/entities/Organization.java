package com.zarlania.api.organizations.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A workspace that owns content and holds members.
 *
 * <p>Every account gets a {@code PERSONAL} organization at registration, named after the username,
 * so nothing downstream ever has to special-case "a user with no organization". {@code GENERAL}
 * organizations are modelled but nothing creates one yet.
 *
 * <p>{@code name} is a {@code citext} column, so uniqueness is case-insensitive.
 */
@Entity
@Table(name = "organizations")
public class Organization extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrganizationType type;

  protected Organization() {}

  /**
   * @param name unique across all organizations, case-insensitively
   * @param type whether this belongs to one account or is shared
   */
  public Organization(String name, OrganizationType type) {
    this.name = name;
    this.type = type;
  }

  /** The display name, also the unique key; matching against it is case-insensitive. */
  public String getName() {
    return name;
  }

  /** Whether this is one account's own organization or a shared one. */
  public OrganizationType getType() {
    return type;
  }
}
