package com.zarlania.api.auth.services;

import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

  // The registration events worth reconstructing afterwards. None of these lines may carry the
  // address: it is the one field a caller can put anybody's data into, and this class's whole
  // purpose is that the outside cannot learn which branch ran — a log holding the address next to
  // the branch name would answer that for anyone who reaches the logs.
  private static final String REGISTRATION_REFUSED_MARKER = "REGISTRATION_REFUSED";

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
      // The username is safe to log where the address is not: it is public by nature, which is the
      // same reason this is the one registration failure the caller is told about.
      log.info(
          "{}: username {} is already taken",
          REGISTRATION_REFUSED_MARKER,
          LogSanitizer.singleLine(username));
      throw UsernameTakenException.forUsername(username);
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
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  public boolean verify(String rawToken) {
    Optional<UUID> userId = emailVerificationService.consume(rawToken);
    // Logged with if/else rather than ifPresentOrElse: SpotBugs attributes a lambda body to a
    // synthetic method, which a suppression on this one does not reach.
    if (userId.isEmpty()) {
      // The token is in neither branch's line. It is a live credential right up until consume()
      // redeems it, and this is exactly the branch where an unredeemed one is still usable.
      log.info("Verification refused: token unknown, expired, or already used");
      return false;
    }
    userService.markEmailVerified(userId.get());
    log.info("Email verified for user {}", userId.get());
    return true;
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
        // user.email(), not the address the caller typed: users.email is citext, so the lookup
        // matched case-insensitively, while an SMTP local part is allowed to be case-sensitive.
        // Mailing the caller's spelling would let someone ask for VICTIM@example.com and have the
        // account's live verification token delivered to a mailbox that may not be the owner's.
        .ifPresent(user -> issueVerification(user.email(), user.id()));
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
      throw UsernameTakenException.forUsername(username);
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
    if (existing.isEmpty()) {
      // The caller asked about an address that existed a moment ago and does not now: the hourly
      // unverified-account sweep reached it between that check and this lookup. Nothing is left to
      // remind, and the caller already gets the same 202 it would have got, so this ends here
      // rather than dereferencing an empty Optional into a 500.
      log.info("Registration reminder skipped: the account was purged mid-request");
      return;
    }
    User owner = existing.get();
    // owner.email(), not the address the caller typed: see the note on resend above.
    if (!owner.emailVerified()) {
      issueVerification(owner.email(), owner.id());
      return;
    }
    applicationEventPublisher.publishEvent(
        new DuplicateRegistrationAttempted(owner.email(), owner.id()));
  }

  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  private void issueVerification(String email, UUID userId) {
    String rawToken;
    try {
      rawToken = emailVerificationService.issue(userId);
    } catch (DataIntegrityViolationException exception) {
      if (userService.findById(userId).isPresent()) {
        // The account is still there, so this is not the race below — some other integrity rule
        // broke and the caller should hear about it rather than get a silent 202.
        throw exception;
      }
      // email_verification_tokens.user_id is a real foreign key, and the account it named was
      // purged by the unverified-account sweep between the lookup that found it and this insert.
      // There is nobody left to email, and both callers answer 202 regardless, so this ends here.
      log.info("Verification not issued for user {}: the account was purged mid-request", userId);
      return;
    }
    // The token is never logged, here or anywhere: only its hash is stored, so a log line holding
    // the raw value is a usable credential for as long as the log is retained.
    log.info("Verification issued for user {}", userId);
    applicationEventPublisher.publishEvent(new VerificationEmailRequested(email, rawToken, userId));
  }
}
