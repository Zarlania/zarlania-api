package com.zarlania.api.auth.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One redeemable refresh token, and one row in the family it belongs to.
 *
 * <p>A family is every token descended from a single login. Rotation redeems a token and issues its
 * successor into the same family, so a stolen token that is replayed after the legitimate client
 * has already rotated is detectable: the row is present but already used, which revokes the family
 * outright rather than letting two clients hold live sessions.
 *
 * <p>Only the SHA-256 hash of the token is stored. A database disclosure therefore yields nothing
 * redeemable, and {@code user_id} / {@code organization_id} are plain foreign-key ids rather than
 * mapped relations, because both belong to other domains.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity extends BaseEntity {

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

  protected RefreshTokenEntity() {}

  /**
   * Starts or extends a family. The token is live from here until it is redeemed, revoked, or the
   * family expires.
   *
   * @param familyId groups this token with every other descended from the same login
   * @param tokenHash the SHA-256 hash of the raw token; the raw value is never stored
   * @param familyExpiresAt the absolute ceiling on the whole family, not just this token
   */
  public RefreshTokenEntity(
      UUID familyId, UUID userId, UUID organizationId, String tokenHash, Instant familyExpiresAt) {
    this.familyId = familyId;
    this.userId = userId;
    this.organizationId = organizationId;
    this.tokenHash = tokenHash;
    this.familyExpiresAt = familyExpiresAt;
  }

  /** The family this token belongs to; revocation acts on all rows sharing it. */
  public UUID getFamilyId() {
    return familyId;
  }

  /** The user this token authenticates, as a plain foreign-key id into the users domain. */
  public UUID getUserId() {
    return userId;
  }

  /** The organization the minted session is scoped to, as a plain foreign-key id. */
  public UUID getOrganizationId() {
    return organizationId;
  }

  /** The SHA-256 hash of the raw token. The raw token exists only in the client's cookie. */
  public String getTokenHash() {
    return tokenHash;
  }

  /** When the whole family dies, rotation or not. Copied onto each successor unchanged. */
  public Instant getFamilyExpiresAt() {
    return familyExpiresAt;
  }

  /** When this token was redeemed, or {@code null} while it is still unredeemed. */
  public Instant getUsedAt() {
    return usedAt;
  }

  /** When this token was revoked, or {@code null} if it never was. */
  public Instant getRevokedAt() {
    return revokedAt;
  }

  /**
   * Whether this token can still be redeemed: unused, unrevoked, and inside its family's lifetime.
   *
   * @param now the instant to judge against, supplied rather than read so callers share one clock
   */
  public boolean isActive(Instant now) {
    return usedAt == null && revokedAt == null && now.isBefore(familyExpiresAt);
  }

  /**
   * Records that this token has been redeemed. Redeeming it again is the reuse signal that revokes
   * the family.
   */
  public void markUsed(Instant at) {
    this.usedAt = at;
  }

  /** Kills this token. Already-revoked rows keep their original instant when re-revoked. */
  public void revoke(Instant at) {
    this.revokedAt = at;
  }
}
