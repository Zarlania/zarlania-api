package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.entities.OrganizationType;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-tests {@link AuthTokenService} in isolation, in particular the structural half of the login
 * timing-parity fix: that the unknown-identifier branch invokes the same decoy-hashing call the
 * wrong-password branch pays for organically (via {@link CredentialsService#passwordMatches}).
 * Elapsed time itself is not asserted — timing assertions are flaky by nature — only that the
 * encoder call happens on the path that would otherwise skip it.
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

  private static final String IDENTIFIER = "person";
  private static final String PASSWORD = "correct-horse-battery";
  private static final String ACCESS_TOKEN = "access-token";
  private static final String NEW_RAW_REFRESH = "new-raw-refresh";

  @Mock private UserService userService;
  @Mock private CredentialsService credentialsService;
  @Mock private OrganizationService organizationService;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private JwtService jwtService;

  private AuthTokenService service;

  @BeforeEach
  void setUp() {
    service =
        new AuthTokenService(
            userService, credentialsService, organizationService, refreshTokenService, jwtService);
  }

  @Test
  void loginWithAnUnknownIdentifierHashesADecoyPasswordThenThrowsInvalidCredentials() {
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.login(IDENTIFIER, PASSWORD))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

    verify(credentialsService).hashDecoyPassword();
    verify(credentialsService, never()).passwordMatches(any(UUID.class), any(String.class));
    verifyNoInteractions(organizationService, refreshTokenService, jwtService);
  }

  @Test
  void loginWithAWrongPasswordThrowsInvalidCredentialsWithoutHashingADecoy() {
    UUID userId = UUID.randomUUID();
    UserDto user = new UserDto(userId, "person@example.com", IDENTIFIER, true);
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(false);

    assertThatThrownBy(() -> service.login(IDENTIFIER, PASSWORD))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS));

    // passwordMatches already runs Argon2id on a real credential row, so a decoy hash here
    // would just double-pay a cost that is already parity with the happy path.
    verify(credentialsService, never()).hashDecoyPassword();
  }

  @Test
  void loginWithACorrectPasswordButAnUnverifiedEmailThrowsEmailUnverified() {
    UUID userId = UUID.randomUUID();
    UserDto user = new UserDto(userId, "person@example.com", IDENTIFIER, false);
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(true);

    assertThatThrownBy(() -> service.login(IDENTIFIER, PASSWORD))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.EMAIL_UNVERIFIED));

    verifyNoInteractions(organizationService, refreshTokenService, jwtService);
  }

  @Test
  void loginHappyPathMintsAnAccessTokenScopedToThePersonalOrganizationAndStartsAFamily() {
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    UserDto user = new UserDto(userId, "person@example.com", IDENTIFIER, true);
    OrganizationDto org = new OrganizationDto(orgId, "person's Space", OrganizationType.PERSONAL);
    IssuedRefreshToken issued = new IssuedRefreshToken("raw-refresh", Instant.now());
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(true);
    when(organizationService.personalOrganizationOf(userId)).thenReturn(Optional.of(org));
    when(refreshTokenService.startFamily(userId, orgId)).thenReturn(issued);
    when(jwtService.mint(userId, orgId, TokenKinds.USER)).thenReturn(ACCESS_TOKEN);

    AuthTokenService.MintedSession session = service.login(IDENTIFIER, PASSWORD);

    assertThat(session.accessToken()).isEqualTo(ACCESS_TOKEN);
    assertThat(session.refresh()).isEqualTo(issued);
  }

  @Test
  void refreshMapsAnInvalidRefreshTokenExceptionToInvalidCredentials() {
    when(refreshTokenService.rotate("raw")).thenThrow(new InvalidRefreshTokenException());

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void refreshMapsAReusedRefreshTokenExceptionToInvalidCredentials() {
    when(refreshTokenService.rotate("raw")).thenThrow(new ReusedRefreshTokenException());

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
  }

  @Test
  void refreshHappyPathMintsANewAccessTokenAndCarriesTheRotatedRefreshToken() {
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    Instant familyExpiresAt = Instant.now();
    RefreshRotation rotation = new RefreshRotation(NEW_RAW_REFRESH, userId, orgId, familyExpiresAt);
    when(refreshTokenService.rotate("raw")).thenReturn(rotation);
    when(jwtService.mint(userId, orgId, TokenKinds.USER)).thenReturn(ACCESS_TOKEN);

    AuthTokenService.MintedSession session = service.refresh("raw");

    assertThat(session.accessToken()).isEqualTo(ACCESS_TOKEN);
    assertThat(session.refresh())
        .isEqualTo(new IssuedRefreshToken(NEW_RAW_REFRESH, familyExpiresAt));
  }

  @Test
  void logoutDelegatesToRevokeFamilyOf() {
    service.logout("raw");

    verify(refreshTokenService).revokeFamilyOf("raw");
  }
}
