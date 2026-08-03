package com.zarlania.api.credentials.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single-use, expiring proof that someone can read the address they registered with.
 *
 * <p>Only the SHA-256 hash is stored, so a database disclosure yields nothing that can be
 * presented, and {@code user_id} is a plain foreign-key id because users is a different domain.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken extends BaseEntity {

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  protected EmailVerificationToken() {}

  /**
   * @param tokenHash the SHA-256 hash of the emailed token; the raw value is never stored
   * @param expiresAt when the token stops being usable, whether or not it was consumed
   */
  public EmailVerificationToken(UUID userId, String tokenHash, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  /** The account this token verifies, as a plain foreign-key id into the users domain. */
  public UUID getUserId() {
    return userId;
  }

  /** The SHA-256 hash of the emailed token. The raw token exists only in the sent message. */
  public String getTokenHash() {
    return tokenHash;
  }

  /** When this token stops being usable, consumed or not. */
  public Instant getExpiresAt() {
    return expiresAt;
  }

  /** When this token was redeemed, or {@code null} while it is still outstanding. */
  public Instant getConsumedAt() {
    return consumedAt;
  }

  /**
   * Whether this token can still be redeemed: unconsumed and unexpired.
   *
   * @param now the instant to judge against, supplied rather than read so callers share one clock
   */
  public boolean isUsable(Instant now) {
    return consumedAt == null && now.isBefore(expiresAt);
  }

  /** Marks the token spent, which is what makes verification single-use. */
  public void consume(Instant at) {
    this.consumedAt = at;
  }
}
