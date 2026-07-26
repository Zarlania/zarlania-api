package com.zarlania.api.users.services;

import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository users;
  private final Clock clock;

  @Transactional
  public UserDto createUnverified(String email, String username) {
    return toDto(users.save(new User(email, username)));
  }

  @Transactional(readOnly = true)
  public Optional<UserDto> findByIdentifier(String emailOrUsername) {
    return users
        .findByEmail(emailOrUsername)
        .or(() -> users.findByUsername(emailOrUsername))
        .map(this::toDto);
  }

  @Transactional(readOnly = true)
  public Optional<UserDto> findById(UUID id) {
    return users.findById(id).map(this::toDto);
  }

  @Transactional(readOnly = true)
  public boolean usernameExists(String username) {
    return users.existsByUsername(username);
  }

  @Transactional(readOnly = true)
  public boolean emailExists(String email) {
    return users.existsByEmail(email);
  }

  @Transactional
  public void markEmailVerified(UUID userId) {
    users.findById(userId).orElseThrow().markEmailVerified(clock.instant());
  }

  private UserDto toDto(User user) {
    return new UserDto(user.getId(), user.getEmail(), user.getUsername(), user.isEmailVerified());
  }
}
