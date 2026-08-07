package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zarlania.api.auth.events.DuplicateRegistrationAttempted;
import com.zarlania.api.auth.events.VerificationEmailRequested;
import com.zarlania.api.auth.exceptions.UsernameTakenException;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.credentials.services.EmailVerificationService;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        .isInstanceOf(UsernameTakenException.class);

    verify(userService, never()).emailExists(any(String.class));
    verifyNoInteractions(credentialsService, accountCreator, emailVerificationService, events);
  }

  @Test
  void registerWithAnAlreadyVerifiedEmailHashesADecoyPasswordInsteadOfCreatingAnAccount() {
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(true);
    when(userService.findByIdentifier(EMAIL))
        .thenReturn(Optional.of(new User(UUID.randomUUID(), EMAIL, USERNAME, true)));

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
        .thenReturn(Optional.of(new User(existingId, EMAIL, USERNAME, false)));
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
        .thenReturn(Optional.of(new User(existingId, EMAIL, USERNAME, false)));
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
        .isInstanceOf(UsernameTakenException.class);

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
    User verifiedUser = new User(UUID.randomUUID(), EMAIL, USERNAME, true);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.of(verifiedUser));

    service.resend(EMAIL);

    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(emailVerificationService, events);
  }

  @Test
  void resendForAnUnverifiedEmailHashesADecoyPasswordAndAlsoIssuesAFreshToken() {
    UUID userId = UUID.randomUUID();
    User unverifiedUser = new User(userId, EMAIL, USERNAME, false);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.of(unverifiedUser));
    when(emailVerificationService.issue(userId)).thenReturn("fresh-token");

    service.resend(EMAIL);

    verify(credentialsService).hashDecoyPassword();
    verify(emailVerificationService).issue(userId);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(VerificationEmailRequested.class);
  }

  // users.email is citext, so the lookup matches whatever spelling the caller typed — but an SMTP
  // local part may be case-sensitive, so the two spellings can be two different mailboxes. Mailing
  // the caller's would let anyone ask for VICTIM@example.com and have a live verification token for
  // the victim's account delivered somewhere the victim does not read.
  @ParameterizedTest(name = "{0} is mailed as the address on record")
  @ValueSource(strings = {"PERSON@example.com", "Person@Example.COM"})
  void resendMailsTheAddressOnRecordRatherThanTheSpellingTheCallerTyped(String typedByCaller) {
    UUID userId = UUID.randomUUID();
    when(userService.findByIdentifier(typedByCaller))
        .thenReturn(Optional.of(new User(userId, EMAIL, USERNAME, false)));
    when(emailVerificationService.issue(userId)).thenReturn("fresh-token");

    service.resend(typedByCaller);

    ArgumentCaptor<VerificationEmailRequested> published =
        ArgumentCaptor.forClass(VerificationEmailRequested.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue().email()).isEqualTo(EMAIL);
  }

  // Same reasoning on the registration side, where the address travels with the duplicate-attempt
  // notice instead of a verification token.
  @Test
  void registeringAgainstAnExistingAccountMailsTheAddressOnRecordNotTheSpellingTyped() {
    String typedByCaller = "PERSON@example.com";
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(typedByCaller)).thenReturn(true);
    when(userService.findByIdentifier(typedByCaller))
        .thenReturn(Optional.of(new User(UUID.randomUUID(), EMAIL, USERNAME, true)));

    service.register(typedByCaller, USERNAME, PASSWORD);

    ArgumentCaptor<DuplicateRegistrationAttempted> published =
        ArgumentCaptor.forClass(DuplicateRegistrationAttempted.class);
    verify(events).publishEvent(published.capture());
    assertThat(published.getValue().email()).isEqualTo(EMAIL);
  }

  // The hourly unverified-account sweep can delete the account between emailExists saying yes and
  // this second lookup running. Dereferencing the empty Optional was a 500 on a path documented to
  // answer 202 for every input, which would have made the race an enumeration signal in itself.
  @Test
  void registeringAgainstAnAccountPurgedMidRequestEndsQuietlyRatherThanFailing() {
    when(userService.usernameExists(USERNAME)).thenReturn(false);
    when(userService.emailExists(EMAIL)).thenReturn(true);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.empty());

    service.register(EMAIL, USERNAME, PASSWORD);

    verify(credentialsService).hashDecoyPassword();
    verifyNoInteractions(accountCreator, emailVerificationService, events);
  }

  // The same sweep, one step later: the account survived the lookup and was gone by the time the
  // token insert ran, so email_verification_tokens' foreign key rejected it. Nobody is left to
  // email and the caller's answer does not depend on it, so the request completes.
  @Test
  void resendForAnAccountPurgedBeforeTheTokenInsertEndsQuietlyRatherThanFailing() {
    UUID userId = UUID.randomUUID();
    when(userService.findByIdentifier(EMAIL))
        .thenReturn(Optional.of(new User(userId, EMAIL, USERNAME, false)));
    when(emailVerificationService.issue(userId))
        .thenThrow(new DataIntegrityViolationException("email_verification_tokens_user_id_fkey"));
    when(userService.findById(userId)).thenReturn(Optional.empty());

    service.resend(EMAIL);

    verifyNoInteractions(events);
  }

  // But only when the account really is gone. An integrity violation with the user still present is
  // some other rule breaking, and swallowing it would answer 202 to a resend that never happened.
  @Test
  void resendRethrowsAnIntegrityViolationWhenTheAccountIsStillThere() {
    UUID userId = UUID.randomUUID();
    DataIntegrityViolationException cause = new DataIntegrityViolationException("some_other_check");
    User user = new User(userId, EMAIL, USERNAME, false);
    when(userService.findByIdentifier(EMAIL)).thenReturn(Optional.of(user));
    when(emailVerificationService.issue(userId)).thenThrow(cause);
    when(userService.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.resend(EMAIL)).isSameAs(cause);

    verifyNoInteractions(events);
  }
}
