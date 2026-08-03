package com.zarlania.api.auth.services;

import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.errors.ApiException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.users.dtos.UserDto;
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

  private static final String USERNAME_TAKEN_MESSAGE = "That username is taken";

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final EmailVerificationService emailVerificationService;
  private final AccountCreator accountCreator;
  private final ApplicationEventPublisher events;

  // Deliberately not @Transactional; the transaction lives one level down, in AccountCreator. The
  // two existence checks below are advisory only — nothing stops a competing registration from
  // committing between a check and the insert that follows it — so `users`' own unique constraints
  // on email and username are the real enforcement, and losing to one of them has to be handled
  // from outside the rolled-back transaction. See resolveRegistrationConflict.
  public void register(String email, String username, String rawPassword) {
    if (userService.usernameExists(username)) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN, USERNAME_TAKEN_MESSAGE);
    }
    if (userService.emailExists(email)) {
      credentialsService.hashDecoyPassword();
      remindExistingOwner(email);
      return;
    }
    try {
      accountCreator.createAccount(email, username, rawPassword);
    } catch (DataIntegrityViolationException e) {
      resolveRegistrationConflict(email, username, e);
    }
  }

  // Reached only by the loser of two concurrent registrations, whose transaction has already rolled
  // back. Without this the constraint violation would escape as a generic 500 — which is not just
  // an ugly failure but an enumeration channel, since a caller who fires two requests at once gets
  // a 500 for an email that is free and two indistinguishable 202s for one that is taken.
  //
  // Which constraint lost is re-derived by asking the same questions register() asked, rather than
  // by parsing the constraint name out of the exception: the answers are now unambiguous, because
  // the winning transaction has committed. Username is checked first so that the outcome matches
  // the order of the checks in register() exactly, and the reminder path afterwards is the very
  // same method the sequential "email already registered" case uses — so the two are identical in
  // status, body and the email that follows, which is the whole point.
  private void resolveRegistrationConflict(
      String email, String username, DataIntegrityViolationException cause) {
    if (userService.usernameExists(username)) {
      throw new ApiException(ErrorCode.USERNAME_TAKEN, USERNAME_TAKEN_MESSAGE);
    }
    if (!userService.emailExists(email)) {
      // Some other integrity rule broke — not the race this handler exists for. Rethrowing keeps a
      // real bug loud instead of quietly answering 202 to a registration that never happened.
      throw cause;
    }
    remindExistingOwner(email);
  }

  // Which reminder depends on whether the existing account was ever verified. The spec is explicit:
  // "Re-registering an unverified email before then re-sends verification; it never alters
  // credentials." Someone whose first verification mail landed in spam registers again and needs
  // another link — sending them the duplicate-attempt notice instead would be untrue, would carry
  // no link, and would strand them on a 403 the moment they followed its advice to sign in.
  //
  // Credentials are deliberately left alone on both paths: re-registering must never let a caller
  // who does not control the mailbox overwrite the password on an account someone else created.
  //
  // Timing parity across the two paths is unchanged. Both are reached only after the same decoy
  // Argon2 hash above, which dominates at tens of milliseconds; both publish exactly one event, and
  // both emails are sent after commit, off the request thread. The extra token write on the
  // unverified path is one DELETE plus one INSERT — the same order as the SELECT here, and
  // invisible next to the hash.
  private void remindExistingOwner(String email) {
    Optional<UserDto> existing = userService.findByIdentifier(email);
    if (existing.isPresent() && !existing.get().emailVerified()) {
      issueVerification(email, existing.get().id());
      return;
    }
    events.publishEvent(new DuplicateRegistrationAttempted(email));
  }

  private void issueVerification(String email, UUID userId) {
    String rawToken = emailVerificationService.issue(userId);
    events.publishEvent(new VerificationEmailRequested(email, rawToken));
  }

  @Transactional
  public boolean verify(String rawToken) {
    Optional<UUID> userId = emailVerificationService.consume(rawToken);
    userId.ifPresent(userService::markEmailVerified);
    return userId.isPresent();
  }

  // Deliberately not @Transactional, unlike register: the decoy hash below costs tens of
  // milliseconds on every single call, and a transaction opened around it would hold a pooled
  // connection for all of that time — including on the two branches that then do nothing at all.
  // Each collaborator manages its own transaction instead, and the only write here (issue) commits
  // inside EmailVerificationService's before the event is published. See
  // RegistrationEmailListener's fallbackExecution for what that means for the email.
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
}
