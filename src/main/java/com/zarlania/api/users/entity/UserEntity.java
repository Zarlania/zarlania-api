package com.zarlania.api.users.entity;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user account, identified by a unique email association. Holds no secrets — credentials live in
 * the future identity domain. Internal to the {@code users} domain; cross boundaries via the {@link
 * com.zarlania.api.users.dto.User} DTO, which carries the canonical {@code User} name.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class UserEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "email", nullable = false, length = 320)
  private String email;

  @Setter
  @Column(name = "username", nullable = false, length = 100)
  private String username;
}
