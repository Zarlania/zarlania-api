package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-tests {@link RegistrationService}'s branching in isolation, in particular the structural
 * half of the timing-parity fix: that every early-return branch of {@code register} and {@code
 * resend} calls {@link CredentialsService#hashDecoyPassword()}. Elapsed time itself is not asserted
 * here — timing assertions are flaky by nature — only that the same encoder call the success path
 * makes also happens on the paths that would otherwise skip it.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

  private static final String EMAIL = "person@example.com";
  private static final String USERNAME = "person";
  private static final String PASSWORD = "correct-horse-battery";

  @Mock private UserService userService;
  @Mock private CredentialsService credentialsService;
  @Mock private OrganizationService organizationService;
  @Mock private EmailVerificationService emailVerificationService;
  @Mock private ApplicationEventPublisher events;

  private RegistrationService service;

  @BeforeEach
  void setUp() {
    service =
        new RegistrationService(
            userService, credentialsService, organizationService, emailVerificationService, events);
  }

  @Test
  void registerWithATakenUsernameThrowsWithoutCheckingEmailOrTouchingAnythingElse() {
    when(userService.usernameExists(USERNAME)).thenReturn(true);

    assertThatThrownBy(() -> service.register(EMAIL, USERNAME, PASSWORD))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode()).isEqualTo(ErrorCode.USERNAME_TAKEN));

    verify(userService, never()).emailExists(any(String.class));
    verifyNoInteractions(credentialsService, organizationService, emailVerificationService, events);
  }

  @Test
  void registerWithAnAlreadyRegisteredEmailHashesADecoyPasswordInsteadOfCreatingAnAccount() {
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(true);

    service.register(EMAIL, USERNAME, PASSWORD);

    verify(credentialsService).hashDecoyPassword();
    verify(credentialsService, never()).createPassword(any(UUID.class), any(String.class));
    verify(userService, never()).createUnverified(any(String.class), any(String.class));
    verify(organizationService, never())
        .createPersonalOrganization(any(UUID.class), any(String.class));
    verify(emailVerificationService, never()).issue(any(UUID.class));
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(DuplicateRegistrationAttempted.class);
  }

  @Test
  void registerHappyPathCreatesTheAccountAndNeverHashesADecoyPassword() {
    UUID userId = UUID.randomUUID();
    UserDto user = new UserDto(userId, EMAIL, USERNAME, false);
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(false);
    when(userService.createUnverified(EMAIL, USERNAME)).thenReturn(user);
    when(emailVerificationService.issue(userId)).thenReturn("raw-token");

    service.register(EMAIL, USERNAME, PASSWORD);

    verify(credentialsService).createPassword(userId, PASSWORD);
    verify(credentialsService, never()).hashDecoyPassword();
    verify(organizationService).createPersonalOrganization(userId, USERNAME);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(VerificationEmailRequested.class);
  }

  @Test
  void verifyWithAConsumableTokenMarksTheUserVerifiedAndReturnsTrue() {
    UUID userId = UUID.randomUUID();
    when(emailVerificationService.consume("raw")).thenReturn(Optional.of(userId));

    boolean result = service.verify("raw");

    assertThat(result).isTrue();
    verify(userService).markEmailVerified(userId);
  }

  @Test
  void verifyWithAnUnconsumableTokenReturnsFalseWithoutMarkingAnyoneVerified() {
    when(emailVerificationService.consume("garbage")).thenReturn(Optional.empty());

    boolean result = service.verify("garbage");

    assertThat(result).isFalse();
    verify(userService, never()).markEmailVerified(any(UUID.class));
  }

  @Test
  void resendForAnUnknownEmailHashesADecoyPasswordAndIssuesNoToken() {
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.empty());

    service.resend(EMAIL);

    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(emailVerificationService, events);
  }

  @Test
  void resendForAnAlreadyVerifiedEmailHashesADecoyPasswordAndIssuesNoToken() {
    UserDto verifiedUser = new UserDto(UUID.randomUUID(), EMAIL, USERNAME, true);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.of(verifiedUser));

    service.resend(EMAIL);

    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(emailVerificationService, events);
  }

  @Test
  void resendForAnUnverifiedEmailHashesADecoyPasswordAndAlsoIssuesAFreshToken() {
    UUID userId = UUID.randomUUID();
    UserDto unverifiedUser = new UserDto(userId, EMAIL, USERNAME, false);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.of(unverifiedUser));
    when(emailVerificationService.issue(userId)).thenReturn("fresh-token");

    service.resend(EMAIL);

    verify(credentialsService).hashDecoyPassword();
    verify(emailVerificationService).issue(userId);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(VerificationEmailRequested.class);
  }
}
