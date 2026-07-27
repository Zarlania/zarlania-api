package com.zarlania.api.auth.services;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.credentials.services.CredentialsService;
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

  public MintedSession refresh(String rawRefreshToken) {
    try {
      RefreshRotation rotation = refreshTokenService.rotate(rawRefreshToken);
      return new MintedSession(
          jwtService.mint(rotation.userId(), rotation.organizationId(), TokenKinds.USER),
          new IssuedRefreshToken(rotation.newRaw(), rotation.familyExpiresAt()));
    } catch (InvalidRefreshTokenException | ReusedRefreshTokenException e) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, REFRESH_REJECTED_MESSAGE);
    }
  }

  public void logout(String rawRefreshToken) {
    refreshTokenService.revokeFamilyOf(rawRefreshToken);
  }

  // Split out of login() (rather than the brief's single findByIdentifier().filter(...).
  // orElseThrow() chain) so the unknown-identifier branch can pay Argon2's cost before throwing.
  // Without this, an unknown identifier returns after one fast SELECT while a known identifier
  // with a wrong password additionally runs Argon2id (tens of milliseconds at this service's
  // parameters), and that gap alone lets a caller enumerate valid identifiers purely by timing —
  // the same channel Task 11 closed for register/resend, closed here the same way.
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
  public record MintedSession(String accessToken, IssuedRefreshToken refresh) {}
}
