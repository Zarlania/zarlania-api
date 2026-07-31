package com.zarlania.api.users.services;

import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

  // DTOs, not entities: the caller is UnverifiedAccountCleanup in the auth domain, and CLAUDE.md's
  // rule is that an entity never leaves the domain that owns it. A List is safe to materialize here
  // because the cutoff is days old — the result is the backlog of abandoned signups, not the table.
  @Transactional(readOnly = true)
  public List<UserDto> findUnverifiedOlderThan(Instant cutoff) {
    return users.findByEmailVerifiedAtIsNullAndCreatedAtBefore(cutoff).stream()
        .map(this::toDto)
        .toList();
  }

  // Deleting a user is this domain's job, not the caller's: every other domain holds only a plain
  // user_id FK, so a caller reaching for UserRepository itself would put the order of the dependent
  // deletes outside the domain that owns the row.
  @Transactional
  public void deleteById(UUID userId) {
    users.deleteById(userId);
  }

  // The safe form of deleteById for the cleanup sweep, which lists its candidates in one
  // transaction and purges each of them in another. In the gap between the two, an account can
  // complete /auth/verify — a real user whose verification email sat in spam until the deadline —
  // and purging it then would destroy a live, verified account on the strength of a stale listing.
  // Returns whether a row actually went, so the caller can abandon a purge that lost that race.
  @Transactional
  public boolean deleteIfStillUnverified(UUID userId) {
    return users.deleteByIdAndEmailVerifiedAtIsNull(userId) > 0;
  }

  private UserDto toDto(User user) {
    return new UserDto(user.getId(), user.getEmail(), user.getUsername(), user.isEmailVerified());
  }
}
