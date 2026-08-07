package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.MintedSession;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.exceptions.EmailUnverifiedException;
import com.zarlania.api.auth.exceptions.InvalidCredentialsException;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.dtos.OrganizationType;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
        .isInstanceOf(InvalidCredentialsException.class);

    verify(credentialsService).hashDecoyPassword();
    verify(credentialsService, never()).passwordMatches(any(UUID.class), any(String.class));
    verifyNoInteractions(organizationService, refreshTokenService, jwtService);
  }

  @Test
  void loginWithAWrongPasswordThrowsInvalidCredentialsWithoutHashingADecoy() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "person@example.com", IDENTIFIER, true);
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(false);

    assertThatThrownBy(() -> service.login(IDENTIFIER, PASSWORD))
        .isInstanceOf(InvalidCredentialsException.class);

    // passwordMatches already runs Argon2id on a real credential row, so a decoy hash here
    // would just double-pay a cost that is already parity with the happy path.
    verify(credentialsService, never()).hashDecoyPassword();
  }

  @Test
  void loginWithACorrectPasswordButAnUnverifiedEmailThrowsEmailUnverified() {
    UUID userId = UUID.randomUUID();
    User user = new User(userId, "person@example.com", IDENTIFIER, false);
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(true);

    assertThatThrownBy(() -> service.login(IDENTIFIER, PASSWORD))
        .isInstanceOf(EmailUnverifiedException.class);

    verifyNoInteractions(organizationService, refreshTokenService, jwtService);
  }

  @Test
  void loginHappyPathMintsAnAccessTokenScopedToThePersonalOrganizationAndStartsAFamily() {
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    User user = new User(userId, "person@example.com", IDENTIFIER, true);
    Organization organization =
        new Organization(orgId, "person's Space", OrganizationType.PERSONAL);
    IssuedRefreshToken issued = new IssuedRefreshToken("raw-refresh", Instant.now());
    when(userService.findByIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(credentialsService.passwordMatches(userId, PASSWORD)).thenReturn(true);
    when(organizationService.personalOrganizationOf(userId)).thenReturn(Optional.of(organization));
    when(refreshTokenService.startFamily(userId, orgId)).thenReturn(issued);
    when(jwtService.mint(userId, orgId, TokenKind.USER)).thenReturn(ACCESS_TOKEN);

    MintedSession session = service.login(IDENTIFIER, PASSWORD);

    assertThat(session.accessToken()).isEqualTo(ACCESS_TOKEN);
    assertThat(session.refresh()).isEqualTo(issued);
  }

  @Test
  void refreshLetsAnInvalidRefreshTokenReachTheHandlerUnchanged() {
    when(refreshTokenService.rotate("raw"))
        .thenThrow(InvalidRefreshTokenException.forRejectedToken());

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void refreshLetsAReusedRefreshTokenReachTheHandlerAsItsOwnType() {
    when(refreshTokenService.rotate("raw"))
        .thenThrow(ReusedRefreshTokenException.forRevokedFamily(UUID.randomUUID()));

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(ReusedRefreshTokenException.class);
  }

  @Test
  void refreshHappyPathMintsANewAccessTokenAndCarriesTheRotatedRefreshToken() {
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    Instant familyExpiresAt = Instant.now();
    RefreshRotation rotation = new RefreshRotation(NEW_RAW_REFRESH, userId, orgId, familyExpiresAt);
    when(refreshTokenService.rotate("raw")).thenReturn(rotation);
    when(userService.findById(userId))
        .thenReturn(Optional.of(new User(userId, "person@example.com", IDENTIFIER, true)));
    when(organizationService.isMember(userId, orgId)).thenReturn(true);
    when(jwtService.mint(userId, orgId, TokenKind.USER)).thenReturn(ACCESS_TOKEN);

    MintedSession session = service.refresh("raw");

    assertThat(session.accessToken()).isEqualTo(ACCESS_TOKEN);
    assertThat(session.refresh())
        .isEqualTo(new IssuedRefreshToken(NEW_RAW_REFRESH, familyExpiresAt));
    // Ordered, not merely both-happened: a token minted before the membership is confirmed is a
    // token that exists for an instant regardless of the answer, which is the bug being excluded.
    InOrder inOrder = inOrder(organizationService, jwtService);
    inOrder.verify(organizationService).isMember(userId, orgId);
    inOrder.verify(jwtService).mint(userId, orgId, TokenKind.USER);
  }

  @Test
  void refreshRejectsAndRevokesTheRotatedFamilyWhenTheOrganizationMembershipIsGone() {
    UUID userId = UUID.randomUUID();
    UUID orgId = UUID.randomUUID();
    RefreshRotation rotation = new RefreshRotation(NEW_RAW_REFRESH, userId, orgId, Instant.now());
    when(refreshTokenService.rotate("raw")).thenReturn(rotation);
    when(userService.findById(userId))
        .thenReturn(Optional.of(new User(userId, "person@example.com", IDENTIFIER, true)));
    when(organizationService.isMember(userId, orgId)).thenReturn(false);

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revokeFamilyOf(NEW_RAW_REFRESH);
    verifyNoInteractions(jwtService);
  }

  @Test
  void refreshRejectsAndRevokesTheRotatedFamilyWhenTheUserNoLongerExists() {
    UUID userId = UUID.randomUUID();
    RefreshRotation rotation =
        new RefreshRotation(NEW_RAW_REFRESH, userId, UUID.randomUUID(), Instant.now());
    when(refreshTokenService.rotate("raw")).thenReturn(rotation);
    when(userService.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revokeFamilyOf(NEW_RAW_REFRESH);
    verifyNoInteractions(jwtService);
  }

  @Test
  void refreshRejectsAndRevokesTheRotatedFamilyWhenTheUserIsNoLongerVerified() {
    UUID userId = UUID.randomUUID();
    RefreshRotation rotation =
        new RefreshRotation(NEW_RAW_REFRESH, userId, UUID.randomUUID(), Instant.now());
    User unverified = new User(userId, "person@example.com", IDENTIFIER, false);
    when(refreshTokenService.rotate("raw")).thenReturn(rotation);
    when(userService.findById(userId)).thenReturn(Optional.of(unverified));

    assertThatThrownBy(() -> service.refresh("raw"))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokenService).revokeFamilyOf(NEW_RAW_REFRESH);
    verifyNoInteractions(jwtService);
  }

  @Test
  void logoutDelegatesToRevokeFamilyOf() {
    service.logout("raw");

    verify(refreshTokenService).revokeFamilyOf("raw");
  }
}
