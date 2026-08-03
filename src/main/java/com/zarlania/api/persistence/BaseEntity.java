package com.zarlania.api.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/** Base for every entity: app-generated UUID v4 id and Hibernate-managed audit timestamps. */
@MappedSuperclass
public abstract class BaseEntity {

  @Id @UuidGenerator private UUID id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected BaseEntity() {}

  /** The primary key, assigned on persist and stable for the row's lifetime. */
  public UUID getId() {
    return id;
  }

  /** When the row was first written. Never moves again — the column is not updatable. */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /** When the row was last written. Equal to {@link #getCreatedAt()} until the first update. */
  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
