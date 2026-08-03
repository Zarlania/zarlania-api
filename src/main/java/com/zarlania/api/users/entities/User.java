package com.zarlania.api.users.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String email;

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String username;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  protected User() {}

  public User(String email, String username) {
    this.email = email;
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public String getUsername() {
    return username;
  }

  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  public void markEmailVerified(Instant at) {
    this.emailVerifiedAt = at;
  }
}
