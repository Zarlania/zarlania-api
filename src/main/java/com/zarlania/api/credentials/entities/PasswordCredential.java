package com.zarlania.api.credentials.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One account's password, stored only as an Argon2id hash.
 *
 * <p>Deliberately not a column on {@code User}: keeping proof material in its own domain means a
 * query for a user can never carry a password hash out with it, however carelessly it is written.
 * One row per account — the unique constraint on {@code user_id} is what enforces that.
 */
@Entity
@Table(name = "password_credentials")
public class PasswordCredential extends BaseEntity {

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  protected PasswordCredential() {}

  /**
   * @param userId a plain foreign-key id, because users is a different domain
   * @param passwordHash an Argon2id hash; the raw password is never stored or logged
   */
  public PasswordCredential(UUID userId, String passwordHash) {
    this.userId = userId;
    this.passwordHash = passwordHash;
  }

  /** The account this password belongs to, as a plain foreign-key id into the users domain. */
  public UUID getUserId() {
    return userId;
  }

  /** The Argon2id hash, carrying its own parameters and salt. Verify with the encoder, never ==. */
  public String getPasswordHash() {
    return passwordHash;
  }
}
