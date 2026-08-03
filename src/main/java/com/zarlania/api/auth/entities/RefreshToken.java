package com.zarlania.api.auth.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "family_expires_at", nullable = false)
  private Instant familyExpiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected RefreshToken() {}

  public RefreshToken(
      UUID familyId, UUID userId, UUID organizationId, String tokenHash, Instant familyExpiresAt) {
    this.familyId = familyId;
    this.userId = userId;
    this.organizationId = organizationId;
    this.tokenHash = tokenHash;
    this.familyExpiresAt = familyExpiresAt;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getFamilyExpiresAt() {
    return familyExpiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public boolean isActive(Instant now) {
    return usedAt == null && revokedAt == null && now.isBefore(familyExpiresAt);
  }

  public void markUsed(Instant at) {
    this.usedAt = at;
  }

  public void revoke(Instant at) {
    this.revokedAt = at;
  }
}
