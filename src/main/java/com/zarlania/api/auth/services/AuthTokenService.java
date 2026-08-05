package com.zarlania.api.auth.services;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.MintedSession;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.exceptions.EmailUnverifiedException;
import com.zarlania.api.auth.exceptions.InvalidCredentialsException;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Issues, rotates and revokes the token pair a user session runs on: a short-lived JWT access token
 * plus a long-lived, cookie-borne refresh token.
 *
 * <p>Every failure leaves here as an auth exception naming what went wrong, never as a status code.
 * What a client is told about each one — and which of them are deliberately indistinguishable — is
 * decided by {@code AuthExceptionHandler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthTokenService {

  // Greppable markers on the events a retrospective actually starts from. Rejected logins are the
  // signal that matters most — a run of them against many accounts is credential stuffing, and a
  // run against one is a targeted attempt — so the two rejection causes are logged apart even
  // though the client is told the same thing either way. The log is not the response: keeping the
  // distinction here is what makes it answerable later, and the caller still cannot observe it.
  private static final String LOGIN_REJECTED_MARKER = "LOGIN_REJECTED";
  private static final String SESSION_STARTED_MARKER = "SESSION_STARTED";

  private final UserService userService;
  private final CredentialsService credentialsService;
  private final OrganizationService organizationService;
  private final RefreshTokenService refreshTokenService;
  private final JwtService jwtService;

  /**
   * Authenticates a password and mints a session: an access token plus the first refresh token of a
   * new family.
   *
   * @param identifier either the account's address or its username
   * @throws InvalidCredentialsException for a wrong password and an unknown identifier alike
   * @throws EmailUnverifiedException when the password was right but the address was never proved
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  public MintedSession login(String identifier, String rawPassword) {
    User user = authenticate(identifier, rawPassword);
    if (!user.emailVerified()) {
      log.warn("{}: user {} has not verified its email", LOGIN_REJECTED_MARKER, user.id());
      throw EmailUnverifiedException.forUser(user.id());
    }
    // personalOrganizationOf's absence is unreachable for a real user: RegistrationService
    // creates the personal organization in the same transaction as the user row, and nothing
    // ever deletes either. orElseThrow's bare NoSuchElementException surfacing as a 500 in that
    // impossible case is an acceptable trade-off against inventing an exception for it.
    Organization personal = organizationService.personalOrganizationOf(user.id()).orElseThrow();
    log.info(
        "{}: user {} signed in to organization {}",
        SESSION_STARTED_MARKER,
        user.id(),
        personal.id());
    return mint(user.id(), personal.id());
  }

  /**
   * Rotates a refresh token and mints a new access token against the same session.
   *
   * <p>The two rejection paths are deliberately not caught and merged here. They mean different
   * things to this service — a replay revokes the whole family, an invalid token does not — and
   * flattening them into one would throw that distinction away before anything could act on it. It
   * is the handler's job to answer both identically to the client.
   *
   * @throws InvalidRefreshTokenException if the token is unknown, expired or revoked, or if the
   *     account behind it is no longer able to hold a session
   * @throws ReusedRefreshTokenException if the token was already redeemed, which revokes the whole
   *     family before it is thrown, since a replay is the signal that the token was stolen
   */
  public MintedSession refresh(String rawRefreshToken) {
    RefreshRotation rotation = refreshTokenService.rotate(rawRefreshToken);
    requireLiveVerifiedUser(rotation);
    return new MintedSession(
        jwtService.mint(rotation.userId(), rotation.organizationId(), TokenKind.USER),
        new IssuedRefreshToken(rotation.newRaw(), rotation.familyExpiresAt()));
  }

  /**
   * Ends a session by revoking its whole refresh-token family, so no descendant can be redeemed.
   *
   * <p>Silent for an unknown or already-revoked token: a client asking to be logged out is logged
   * out either way, and answering differently would say whether the token was real.
   */
  public void logout(String rawRefreshToken) {
    refreshTokenService.revokeFamilyOf(rawRefreshToken);
  }

  /**
   * Unreachable today, by invariants rather than by a check: the only user-deletion path
   * (UnverifiedAccountCleanup) clears refresh tokens in the same transaction as the user row, an
   * unverified user cannot log in to obtain a family, and nothing un-verifies an email. But every
   * one of those invariants is implicit, and the first future feature to break one — account
   * deletion, disablement, email change with re-verification — would otherwise leave a dead account
   * holding a live session for the rest of its 30-day family. The rotation that just committed is
   * revoked before rejecting, so the failed refresh cannot itself leave a fresh live token behind.
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  private void requireLiveVerifiedUser(RefreshRotation rotation) {
    boolean verified =
        userService.findById(rotation.userId()).map(User::emailVerified).orElse(false);
    if (verified) {
      return;
    }
    log.warn(
        "Refresh refused: user {} is gone or unverified — revoking the family just rotated",
        rotation.userId());
    refreshTokenService.revokeFamilyOf(rotation.newRaw());
    throw InvalidRefreshTokenException.forRejectedToken();
  }

  /**
   * Split out of login() (rather than a single findByIdentifier().filter(...).orElseThrow() chain)
   * so the unknown-identifier branch can pay Argon2's cost before throwing. Without this, an
   * unknown identifier returns after one fast SELECT while a known identifier with a wrong password
   * additionally runs Argon2id (tens of milliseconds at this service's parameters), and that gap
   * alone lets a caller enumerate valid identifiers purely by timing — the same channel {@link
   * RegistrationService} closes for register and resend, closed here the same way.
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "Every value interpolated into a log line here is a java.util.UUID, whose"
              + " toString() is lowercase hex digits and hyphens by RFC 4122, so there is"
              + " no injectable character to strip. FindSecBugs marks them tainted because"
              + " they were reached through a lookup by caller-supplied identifier, not"
              + " because the logged value itself is caller-supplied.")
  private User authenticate(String identifier, String rawPassword) {
    Optional<User> user = userService.findByIdentifier(identifier);
    if (user.isEmpty()) {
      credentialsService.hashDecoyPassword();
      // No identifier in the line: it is whatever the caller typed, and an address there would put
      // a person — possibly one with no account here at all — into the logs.
      log.warn("{}: no account matched the identifier supplied", LOGIN_REJECTED_MARKER);
      throw InvalidCredentialsException.forRejectedLogin();
    }
    if (!credentialsService.passwordMatches(user.get().id(), rawPassword)) {
      log.warn("{}: wrong password for user {}", LOGIN_REJECTED_MARKER, user.get().id());
      throw InvalidCredentialsException.forRejectedLogin();
    }
    return user.get();
  }

  private MintedSession mint(UUID userId, UUID organizationId) {
    IssuedRefreshToken refresh = refreshTokenService.startFamily(userId, organizationId);
    return new MintedSession(jwtService.mint(userId, organizationId, TokenKind.USER), refresh);
  }
}
