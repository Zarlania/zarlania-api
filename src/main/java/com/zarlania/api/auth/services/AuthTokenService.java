package com.zarlania.api.auth.services;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.errors.ApiException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Issues, rotates and revokes the token pair a user session runs on: a short-lived JWT access token
 * plus a long-lived, cookie-borne refresh token.
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

  // Deliberately identical for both branches of login()'s credential check (unknown identifier,
  // known identifier with a wrong password) — same status, same code, same detail — so a client
  // response can never reveal which one occurred.
  private static final String INVALID_CREDENTIALS_MESSAGE = "Bad credentials";
  private static final String EMAIL_UNVERIFIED_MESSAGE = "Verify your email first";
  private static final String REFRESH_REJECTED_MESSAGE = "Refresh token rejected";

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
   * @throws com.zarlania.api.errors.ApiException with {@code INVALID_CREDENTIALS} for a wrong
   *     password and an unknown identifier alike, or {@code EMAIL_UNVERIFIED} when the password was
   *     right but the address was never proved
   */
  public MintedSession login(String identifier, String rawPassword) {
    UserDto user = authenticate(identifier, rawPassword);
    if (!user.emailVerified()) {
      throw new ApiException(ErrorCode.EMAIL_UNVERIFIED, EMAIL_UNVERIFIED_MESSAGE);
    }
    // personalOrganizationOf's absence is unreachable for a real user: RegistrationService
    // creates the personal organization in the same transaction as the user row, and nothing
    // ever deletes either. orElseThrow's bare NoSuchElementException surfacing as a 500 in that
    // impossible case is an acceptable trade-off against inventing an ErrorCode for it.
    OrganizationDto personal = organizationService.personalOrganizationOf(user.id()).orElseThrow();
    return mint(user.id(), personal.id());
  }

  /**
   * Rotates a refresh token and mints a new access token against the same session.
   *
   * @throws com.zarlania.api.errors.ApiException with {@code INVALID_CREDENTIALS} if the token is
   *     unknown, expired, revoked, or already redeemed — the last of which also revokes the whole
   *     family, since a replay is the signal that the token was stolen
   */
  public MintedSession refresh(String rawRefreshToken) {
    RefreshRotation rotation = rotateOrReject(rawRefreshToken);
    requireLiveVerifiedUser(rotation);
    return new MintedSession(
        jwtService.mint(rotation.userId(), rotation.organizationId(), TokenKinds.USER),
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

  private RefreshRotation rotateOrReject(String rawRefreshToken) {
    try {
      return refreshTokenService.rotate(rawRefreshToken);
    } catch (InvalidRefreshTokenException | ReusedRefreshTokenException e) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, REFRESH_REJECTED_MESSAGE);
    }
  }

  /**
   * Unreachable today, by invariants rather than by a check: the only user-deletion path
   * (UnverifiedAccountCleanup) clears refresh tokens in the same transaction as the user row, an
   * unverified user cannot log in to obtain a family, and nothing un-verifies an email. But every
   * one of those invariants is implicit, and the first future feature to break one — account
   * deletion, disablement, email change with re-verification — would otherwise leave a dead account
   * holding a live session for the rest of its 30-day family. The spec asks for this re-check on
   * refresh explicitly; the rotation that just committed is revoked before rejecting, so the failed
   * refresh cannot itself leave a fresh live token behind.
   */
  private void requireLiveVerifiedUser(RefreshRotation rotation) {
    boolean verified =
        userService.findById(rotation.userId()).map(UserDto::emailVerified).orElse(false);
    if (verified) {
      return;
    }
    refreshTokenService.revokeFamilyOf(rotation.newRaw());
    throw new ApiException(ErrorCode.INVALID_CREDENTIALS, REFRESH_REJECTED_MESSAGE);
  }

  /**
   * Split out of login() (rather than the brief's single findByIdentifier().filter(...).
   * orElseThrow() chain) so the unknown-identifier branch can pay Argon2's cost before throwing.
   * Without this, an unknown identifier returns after one fast SELECT while a known identifier with
   * a wrong password additionally runs Argon2id (tens of milliseconds at this service's
   * parameters), and that gap alone lets a caller enumerate valid identifiers purely by timing —
   * the same channel Task 11 closed for register/resend, closed here the same way.
   */
  private UserDto authenticate(String identifier, String rawPassword) {
    Optional<UserDto> user = userService.findByIdentifier(identifier);
    if (user.isEmpty()) {
      credentialsService.hashDecoyPassword();
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }
    if (!credentialsService.passwordMatches(user.get().id(), rawPassword)) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
    }
    return user.get();
  }

  private MintedSession mint(UUID userId, UUID organizationId) {
    IssuedRefreshToken refresh = refreshTokenService.startFamily(userId, organizationId);
    return new MintedSession(jwtService.mint(userId, organizationId, TokenKinds.USER), refresh);
  }

  /** An access token paired with the refresh token issued alongside it. */
  /**
   * What a successful login or refresh hands back.
   *
   * @param accessToken the short-lived bearer token, returned in the response body
   * @param refresh the long-lived refresh token, which the controller writes to an HttpOnly cookie
   */
  public record MintedSession(String accessToken, IssuedRefreshToken refresh) {}
}
