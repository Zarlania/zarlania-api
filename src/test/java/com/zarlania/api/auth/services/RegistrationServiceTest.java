package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
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
import org.springframework.dao.DataIntegrityViolationException;

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
  @Mock private EmailVerificationService emailVerificationService;
  @Mock private AccountCreator accountCreator;
  @Mock private ApplicationEventPublisher events;

  private RegistrationService service;

  @BeforeEach
  void setUp() {
    service =
        new RegistrationService(
            userService, credentialsService, emailVerificationService, accountCreator, events);
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
    verifyNoInteractions(credentialsService, accountCreator, emailVerificationService, events);
  }

  @Test
  void registerWithAnAlreadyVerifiedEmailHashesADecoyPasswordInsteadOfCreatingAnAccount() {
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(true);
    when(userService.findByIdentifier(EMAIL))
        .thenReturn(Optional.of(new UserDto(UUID.randomUUID(), EMAIL, USERNAME, true)));

    service.register(EMAIL, USERNAME, PASSWORD);

    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(accountCreator);
    verify(emailVerificationService, never()).issue(any(UUID.class));
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(DuplicateRegistrationAttempted.class);
  }

  // The spec: re-registering an *unverified* email re-sends verification and never alters
  // credentials. Sending the duplicate-attempt notice here instead would be untrue, would carry no
  // link, and would leave a user whose first mail went to spam permanently unable to sign in.
  @Test
  void registerWithAnUnverifiedExistingEmailReissuesVerificationAndLeavesCredentialsAlone() {
    UUID existingId = UUID.randomUUID();
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(true);
    when(userService.findByIdentifier(EMAIL))
        .thenReturn(Optional.of(new UserDto(existingId, EMAIL, USERNAME, false)));
    when(emailVerificationService.issue(existingId)).thenReturn("fresh-token");

    service.register(EMAIL, USERNAME, PASSWORD);

    // Same decoy hash the verified branch pays, so the two stay indistinguishable by timing.
    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(accountCreator);
    verify(emailVerificationService).issue(existingId);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(VerificationEmailRequested.class);
  }

  // The rows themselves — and the verification event that follows them — are AccountCreator's job,
  // because they have to commit or roll back together. All this service decides is that the account
  // is new, so that is all this test asserts.
  @Test
  void registerHappyPathDelegatesAccountCreationAndNeverHashesADecoyPassword() {
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(false);

    service.register(EMAIL, USERNAME, PASSWORD);

    verify(accountCreator).createAccount(EMAIL, USERNAME, PASSWORD);
    verify(credentialsService, never()).hashDecoyPassword();
    verifyNoInteractions(events);
  }

  // Both requests pass the existence checks, then the database's unique constraint on users.email
  // rejects whichever commits second. Answering that with a 500 would leak: paired requests get a
  // 500 for an unused email and two identical 202s for one already registered, which is exactly the
  // enumeration channel the decoy hashing elsewhere exists to close.
  @Test
  void registerLosingTheEmailUniquenessRaceAnswersAsIfTheEmailHadAlreadyExisted() {
    UUID existingId = UUID.randomUUID();
    when(userService.usernameExists(USERNAME)).thenReturn(false, false);
    when(userService.emailExists(EMAIL)).thenReturn(false, true);
    doThrow(new DataIntegrityViolationException("users_email_key"))
        .when(accountCreator)
        .createAccount(EMAIL, USERNAME, PASSWORD);
    when(userService.findByIdentifier(EMAIL))
        .thenReturn(Optional.of(new UserDto(existingId, EMAIL, USERNAME, false)));
    when(emailVerificationService.issue(existingId)).thenReturn("fresh-token");

    service.register(EMAIL, USERNAME, PASSWORD);

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(VerificationEmailRequested.class);
  }

  @Test
  void registerLosingTheUsernameUniquenessRaceReportsTheUsernameAsTaken() {
    when(userService.usernameExists(USERNAME)).thenReturn(false, true);
    when(userService.emailExists(EMAIL)).thenReturn(false);
    doThrow(new DataIntegrityViolationException("users_username_key"))
        .when(accountCreator)
        .createAccount(EMAIL, USERNAME, PASSWORD);

    assertThatThrownBy(() -> service.register(EMAIL, USERNAME, PASSWORD))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex ->
                assertThat(((ApiException) ex).getErrorCode()).isEqualTo(ErrorCode.USERNAME_TAKEN));

    verifyNoInteractions(events);
  }

  // A constraint that is not one of the two uniqueness races must stay loud. Swallowing it would
  // answer 202 to a registration that never happened, and hide the bug that caused it.
  @Test
  void registerRethrowsAnIntegrityViolationThatIsNotAUniquenessRace() {
    DataIntegrityViolationException cause = new DataIntegrityViolationException("some_other_check");
    when(userService.usernameExists(USERNAME)).thenReturn(false, false);
    when(userService.emailExists(EMAIL)).thenReturn(false, false);
    doThrow(cause).when(accountCreator).createAccount(EMAIL, USERNAME, PASSWORD);

    assertThatThrownBy(() -> service.register(EMAIL, USERNAME, PASSWORD)).isSameAs(cause);

    verifyNoInteractions(events);
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
