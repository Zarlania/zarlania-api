package com.zarlania.api.users.services;

import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.entities.UserEntity;
import com.zarlania.api.users.repositories.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The users domain's whole surface to the rest of the application.
 *
 * <p>Everything here returns {@link User}, never {@link UserEntity}: an entity never leaves the
 * domain that owns it, so a caller in another domain cannot reach a lazy relation or mutate a row
 * behind this service's back.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final Clock clock;

  /**
   * Creates an account that cannot log in yet. Verification is a separate step, so nothing here
   * checks whether the address is real.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if the address or username is
   *     already taken — the uniqueness constraints are the authority, not a prior existence check
   */
  @Transactional
  public User createUnverified(String email, String username) {
    return toUser(userRepository.save(new UserEntity(email, username)));
  }

  /**
   * Finds an account by either of the things a person can log in with, address first.
   *
   * <p>Both columns are unique and {@code citext}, so the order only decides which lookup runs
   * first, never which account is found.
   */
  @Transactional(readOnly = true)
  public Optional<User> findByIdentifier(String emailOrUsername) {
    return userRepository
        .findByEmail(emailOrUsername)
        .or(() -> userRepository.findByUsername(emailOrUsername))
        .map(this::toUser);
  }

  /** Finds an account by id, or empty if no such row exists. */
  @Transactional(readOnly = true)
  public Optional<User> findById(UUID id) {
    return userRepository.findById(id).map(this::toUser);
  }

  /** Whether a username is taken, case-insensitively. */
  @Transactional(readOnly = true)
  public boolean usernameExists(String username) {
    return userRepository.existsByUsername(username);
  }

  /** Whether an address is taken, case-insensitively. */
  @Transactional(readOnly = true)
  public boolean emailExists(String email) {
    return userRepository.existsByEmail(email);
  }

  /**
   * Records that an account has proved its address, stamping the shared clock. This is what
   * unblocks login.
   *
   * @throws java.util.NoSuchElementException if the account no longer exists — unreachable from the
   *     verification path, which has just read the row inside the same transaction
   */
  @Transactional
  public void markEmailVerified(UUID userId) {
    userRepository.findById(userId).orElseThrow().markEmailVerified(clock.instant());
  }

  /**
   * Lists the accounts old enough to purge: registered before {@code cutoff} and still unverified.
   *
   * <p>A {@link List} is safe to materialize because the cutoff is days old — the result is the
   * backlog of abandoned signups, not the table.
   */
  @Transactional(readOnly = true)
  public List<User> findUnverifiedOlderThan(Instant cutoff) {
    return userRepository.findByEmailVerifiedAtIsNullAndCreatedAtBefore(cutoff).stream()
        .map(this::toUser)
        .toList();
  }

  /**
   * Deletes an account outright.
   *
   * <p>Deleting a user is this domain's job, not the caller's: every other domain holds only a
   * plain {@code user_id} foreign key, so a caller reaching for the repository itself would put the
   * order of the dependent deletes outside the domain that owns the row.
   */
  @Transactional
  public void deleteById(UUID userId) {
    userRepository.deleteById(userId);
  }

  /**
   * The safe form of {@link #deleteById} for the cleanup sweep, which lists its candidates in one
   * transaction and purges each of them in another.
   *
   * <p>In the gap between the two, an account can complete verification — a real person whose
   * verification email sat in spam until the deadline — and purging it then would destroy a live,
   * verified account on the strength of a stale listing.
   *
   * @return whether a row actually went, so the caller can abandon a purge that lost that race
   */
  @Transactional
  public boolean deleteIfStillUnverified(UUID userId) {
    return userRepository.deleteByIdAndEmailVerifiedAtIsNull(userId) > 0;
  }

  private User toUser(UserEntity user) {
    return new User(user.getId(), user.getEmail(), user.getUsername(), user.isEmailVerified());
  }
}
