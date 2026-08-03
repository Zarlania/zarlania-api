package com.zarlania.api.credentials.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "password_credentials")
public class PasswordCredential extends BaseEntity {

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  protected PasswordCredential() {}

  public PasswordCredential(UUID userId, String passwordHash) {
    this.userId = userId;
    this.passwordHash = passwordHash;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }
}
