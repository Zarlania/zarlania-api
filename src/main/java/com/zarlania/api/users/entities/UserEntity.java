package com.zarlania.api.users.entities;

import com.zarlania.api.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An account holder. Carries identity only — no password hash and no tokens, both of which live in
 * the credentials domain so that a query for a user can never return proof material with it.
 *
 * <p>{@code email} and {@code username} are {@code citext} columns, so uniqueness and lookup are
 * case-insensitive: "Bob" and "bob" are one account, not two.
 */
@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String email;

  @Column(nullable = false, unique = true, columnDefinition = "citext")
  private String username;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  protected UserEntity() {}

  /**
   * Creates an unverified account. Verification is a later, separate step — {@link
   * #markEmailVerified} — because an address nobody has proved they own must not be able to log in.
   */
  public UserEntity(String email, String username) {
    this.email = email;
    this.username = username;
  }

  /**
   * The address in the spelling it was registered with; matching against it is case-insensitive.
   */
  public String getEmail() {
    return email;
  }

  /**
   * The username in the spelling it was registered with; matching against it is case-insensitive.
   */
  public String getUsername() {
    return username;
  }

  /** Whether the address has been proved. False blocks login, and it is false until proved. */
  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  /** Records that the address has been proved, which is what unblocks login. */
  public void markEmailVerified(Instant at) {
    this.emailVerifiedAt = at;
  }
}
