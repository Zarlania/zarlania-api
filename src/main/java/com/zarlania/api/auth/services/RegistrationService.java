package com.zarlania.api.auth.services;

import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates registration, verification and resend. Enumeration safety is deliberate: a caller
 * can never distinguish "email already registered" from "verification email sent" — nor, on resend,
 * "unknown email" from "already verified" from "unverified, email resent" — by status, response
 * body, or elapsed time. Status and body are equal by construction (every branch of {@code
 * register} and {@code resend} returns the same way). Timing equality is deliberate too: every
 * early-return branch below calls {@link CredentialsService#hashDecoyPassword()} to pay the same
 * dominant Argon2id cost the success path pays via {@link CredentialsService#createPassword},
 * because that cost (tens of milliseconds) otherwise dwarfs everything else these methods do and
 * would trivially distinguish the branches over a network. Which email actually goes out is decided
 * entirely after commit, by {@link RegistrationEmailListener}.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final EmailVerificationService emailVerificationService;
  private final AccountCreator accountCreator;
  private final ApplicationEventPublisher applicationEventPublisher;

  /**
   * Registers an account and starts email verification.
   *
   * <p>Deliberately not {@code @Transactional}; the transaction lives one level down, in {@code
   * AccountCreator}. The existence checks are advisory only — nothing stops a competing
   * registration from committing between a check and the insert that follows it — so the {@code
   * users} table's own unique constraints are the real enforcement, and losing to one of them has
   * to be handled from outside the rolled-back transaction.
   *
   * @throws UsernameTakenException if the username is already in use. An address already in use is
   *     <em>not</em> an error: the caller gets the same answer either way, and the existing owner
   *     is emailed instead, so the response cannot be used to enumerate accounts.
   */
  public void register(String email, String username, String rawPassword) {
    if (userService.usernameExists(username)) {
      throw new UsernameTakenException();
    }
    if (userService.emailExists(email)) {
      credentialsService.hashDecoyPassword();
      remindExistingOwner(email);
      return;
    }
    try {
      accountCreator.createAccount(email, username, rawPassword);
    } catch (DataIntegrityViolationException exception) {
      resolveRegistrationConflict(email, username, exception);
    }
  }

  /**
   * Redeems a verification token and marks the account's address proved.
   *
   * @return whether the token was redeemable; false covers unknown, expired and already-consumed
   *     alike, which the caller is expected to answer identically
   */
  @Transactional
  public boolean verify(String rawToken) {
    Optional<UUID> userId = emailVerificationService.consume(rawToken);
    userId.ifPresent(userService::markEmailVerified);
    return userId.isPresent();
  }

  /**
   * Sends a fresh verification email, if the address exists and is not already verified.
   *
   * <p>Silent about which of those it was: an unknown address, an already-verified one and a real
   * resend are indistinguishable to the caller, in timing as well as in response.
   *
   * <p>Deliberately not {@code @Transactional}, unlike registration: the decoy hash costs tens of
   * milliseconds on every call, and a transaction opened around it would hold a pooled connection
   * for all of that time — including on the two branches that then do nothing at all. Each
   * collaborator manages its own transaction instead.
   */
  public void resend(String email) {
    // Unconditional, not just on the branches that would otherwise skip it: resend has no
    // "real" Argon2 work of its own (unlike register's createPassword) for a decoy to stand in
    // for, so every outcome — unknown email, already verified, resent — needs the same paid-up-
    // front cost to stay indistinguishable from each other. Hoisting it out of a transaction
    // changes what it holds, not when it runs or what it costs.
    credentialsService.hashDecoyPassword();
    userService
        .findByIdentifier(email)
        .filter(user -> !user.emailVerified())
        .ifPresent(user -> issueVerification(email, user.id()));
  }

  /**
   * Reached only by the loser of two concurrent registrations, whose transaction has already rolled
   * back. Without this the constraint violation would escape as a generic 500 — which is not just
   * an ugly failure but an enumeration channel, since a caller who fires two requests at once gets
   * a 500 for an email that is free and two indistinguishable 202s for one that is taken.
   *
   * <p>Which constraint lost is re-derived by asking the same questions register() asked, rather
   * than by parsing the constraint name out of the exception: the answers are now unambiguous,
   * because the winning transaction has committed. Username is checked first so that the outcome
   * matches the order of the checks in register() exactly, and the reminder path afterwards is the
   * very same method the sequential "email already registered" case uses — so the two are identical
   * in status, body and the email that follows, which is the whole point.
   */
  private void resolveRegistrationConflict(
      String email, String username, DataIntegrityViolationException cause) {
    if (userService.usernameExists(username)) {
      throw new UsernameTakenException();
    }
    if (!userService.emailExists(email)) {
      // Some other integrity rule broke — not the race this handler exists for. Rethrowing keeps a
      // real bug loud instead of quietly answering 202 to a registration that never happened.
      throw cause;
    }
    remindExistingOwner(email);
  }

  /**
   * Which reminder depends on whether the existing account was ever verified. Re-registering an
   * unverified email re-sends verification and never alters credentials. Someone whose first
   * verification mail landed in spam registers again and needs another link — sending them the
   * duplicate-attempt notice instead would be untrue, would carry no link, and would strand them on
   * a 403 the moment they followed its advice to sign in.
   *
   * <p>Credentials are deliberately left alone on both paths: re-registering must never let a
   * caller who does not control the mailbox overwrite the password on an account someone else
   * created.
   *
   * <p>Timing parity across the two paths is unchanged. Both are reached only after the same decoy
   * Argon2 hash above, which dominates at tens of milliseconds; both publish exactly one event, and
   * both emails are sent after commit, off the request thread. The extra token write on the
   * unverified path is one DELETE plus one INSERT — the same order as the SELECT here, and
   * invisible next to the hash.
   */
  private void remindExistingOwner(String email) {
    Optional<User> existing = userService.findByIdentifier(email);
    if (existing.isPresent() && !existing.get().emailVerified()) {
      issueVerification(email, existing.get().id());
      return;
    }
    applicationEventPublisher.publishEvent(new DuplicateRegistrationAttempted(email));
  }

  private void issueVerification(String email, UUID userId) {
    String rawToken = emailVerificationService.issue(userId);
    applicationEventPublisher.publishEvent(new VerificationEmailRequested(email, rawToken));
  }
}
