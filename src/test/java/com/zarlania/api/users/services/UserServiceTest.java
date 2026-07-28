package com.zarlania.api.users.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.entities.User;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  private static final Instant FIXED_INSTANT = Instant.parse("2026-07-26T00:00:00Z");

  @Mock private UserRepository users;

  private UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(users, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
  }

  @Test
  void createUnverifiedSavesAndReturnsUnverifiedDto() {
    when(users.save(any(User.class))).thenReturn(new User("new@example.com", "newuser"));

    UserDto dto = service.createUnverified("new@example.com", "newuser");

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("new@example.com");
    assertThat(saved.getValue().getUsername()).isEqualTo("newuser");

    assertThat(dto.email()).isEqualTo("new@example.com");
    assertThat(dto.username()).isEqualTo("newuser");
    assertThat(dto.emailVerified()).isFalse();
  }

  @Test
  void findByIdentifierFallsBackFromEmailToUsername() {
    User user = new User("someone@example.com", "someone");
    when(users.findByEmail("someone")).thenReturn(Optional.empty());
    when(users.findByUsername("someone")).thenReturn(Optional.of(user));

    Optional<UserDto> found = service.findByIdentifier("someone");

    assertThat(found).isPresent();
    assertThat(found.get().username()).isEqualTo("someone");
  }

  @Test
  void findByIdentifierReturnsEmptyWhenNeitherEmailNorUsernameMatch() {
    when(users.findByEmail("nobody")).thenReturn(Optional.empty());
    when(users.findByUsername("nobody")).thenReturn(Optional.empty());

    assertThat(service.findByIdentifier("nobody")).isEmpty();
  }

  @Test
  void findByIdReturnsMappedDtoReflectingVerifiedState() {
    User user = new User("found@example.com", "found");
    user.markEmailVerified(FIXED_INSTANT);
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.of(user));

    Optional<UserDto> found = service.findById(id);

    assertThat(found).isPresent();
    assertThat(found.get().emailVerified()).isTrue();
  }

  @Test
  void usernameExistsDelegatesToRepository() {
    when(users.existsByUsername("taken")).thenReturn(true);

    assertThat(service.usernameExists("taken")).isTrue();
  }

  @Test
  void emailExistsDelegatesToRepository() {
    when(users.existsByEmail("taken@example.com")).thenReturn(true);

    assertThat(service.emailExists("taken@example.com")).isTrue();
  }

  // The point of the method: the auth domain's cleanup sweep gets DTOs it can iterate, never User
  // entities, so no entity leaves this domain.
  @Test
  void findUnverifiedOlderThanReturnsDtosNotEntities() {
    Instant cutoff = FIXED_INSTANT.minus(Duration.ofDays(7));
    when(users.findByEmailVerifiedAtIsNullAndCreatedAtBefore(cutoff))
        .thenReturn(List.of(new User("stale@example.com", "stale")));

    List<UserDto> found = service.findUnverifiedOlderThan(cutoff);

    assertThat(found)
        .singleElement()
        .satisfies(dto -> assertThat(dto.username()).isEqualTo("stale"));
  }

  @Test
  void deleteByIdDelegatesToTheRepository() {
    UUID id = UUID.randomUUID();

    service.deleteById(id);

    verify(users).deleteById(id);
  }

  @Test
  void markEmailVerifiedStampsTheFixedClockInstant() {
    User user = spy(new User("verify@example.com", "verify"));
    UUID id = UUID.randomUUID();
    when(users.findById(id)).thenReturn(Optional.of(user));

    service.markEmailVerified(id);

    verify(user).markEmailVerified(FIXED_INSTANT);
  }
}
