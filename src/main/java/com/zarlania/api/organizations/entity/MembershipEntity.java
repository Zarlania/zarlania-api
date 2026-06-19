package com.zarlania.api.organizations.entity;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's membership in an organization. The {@code organization} association is same-domain; the
 * {@code userId} is a plain column — never a JPA relationship to the {@code users} domain
 * (ADR-0011). Referential integrity for {@code userId} is enforced by the {@code
 * memberships.user_id} → {@code users.id} foreign key declared in the V2 migration, not by an ORM
 * association.
 */
@Entity
@Table(name = "memberships")
@Getter
@NoArgsConstructor
public class MembershipEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "organization_id", nullable = false)
  private OrganizationEntity organization;

  @Setter
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Setter
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private MembershipRole role;
}
