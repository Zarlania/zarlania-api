package com.zarlania.api.credentials.services;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.credentials.CredentialsProperties;
import com.zarlania.api.credentials.entities.PasswordCredential;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class CredentialsServiceTest {

  private static final String CORRECT_PASSWORD = "correct horse battery staple!";
  private static final String WRONG_PASSWORD = "wrong horse battery staple!";

  // Deliberately weak Argon2 parameters (vs. the OWASP baseline in
  // PasswordEncoderConfig) so this unit test hashes fast; a real encoder is used
  // rather than a mock so the test proves encode/verify actually round-trip.
  private static final PasswordEncoder ENCODER = new Argon2PasswordEncoder(16, 32, 1, 1024, 1);

  private static final CredentialsProperties PROPERTIES =
      new CredentialsProperties(Duration.ofHours(24), 4);
  private static final Duration BLOCKED_CALL_GRACE = Duration.ofMillis(300);
  private static final Duration UNBLOCKED_CALL_TIMEOUT = Duration.ofSeconds(5);

  @Mock private PasswordCredentialRepository credentials;
  @Mock private EmailVerificationTokenRepository verificationTokens;
  @Mock private PasswordEncoder mockPasswordEncoder;

  private CredentialsService service;

  @BeforeEach
  void setUp() {
    service = new CredentialsService(credentials, verificationTokens, ENCODER, PROPERTIES);
  }

  @Test
  void createPasswordSavesAnEncodedHashThatVerifiesAgainstTheRawPassword() {
    UUID userId = UUID.randomUUID();

    service.createPassword(userId, CORRECT_PASSWORD);

    ArgumentCaptor<PasswordCredential> saved = ArgumentCaptor.forClass(PasswordCredential.class);
    verify(credentials).save(saved.capture());
    assertThat(saved.getValue().getUserId()).isEqualTo(userId);
    assertThat(ENCODER.matches(CORRECT_PASSWORD, saved.getValue().getPasswordHash())).isTrue();
  }

  @Test
  void passwordMatchesReturnsTrueForTheStoredHashOfTheCorrectPassword() {
    UUID userId = UUID.randomUUID();
    PasswordCredential stored = new PasswordCredential(userId, ENCODER.encode(CORRECT_PASSWORD));
    when(credentials.findByUserId(userId)).thenReturn(Optional.of(stored));

    assertThat(service.passwordMatches(userId, CORRECT_PASSWORD)).isTrue();
  }

  @Test
  void passwordMatchesReturnsFalseForAWrongPassword() {
    UUID userId = UUID.randomUUID();
    PasswordCredential stored = new PasswordCredential(userId, ENCODER.encode(CORRECT_PASSWORD));
    when(credentials.findByUserId(userId)).thenReturn(Optional.of(stored));

    assertThat(service.passwordMatches(userId, WRONG_PASSWORD)).isFalse();
  }

  @Test
  void passwordMatchesReturnsFalseForAnUnknownUserId() {
    UUID userId = UUID.randomUUID();
    when(credentials.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service.passwordMatches(userId, CORRECT_PASSWORD)).isFalse();
  }

  // Uses a mock encoder rather than the shared real ENCODER above: the point of this test is
  // the structural property that hashDecoyPassword() drives the same PasswordEncoder every real
  // hash goes through (so RegistrationService's timing-parity calls track PasswordEncoderConfig's
  // real parameters automatically), not the hash output itself.
  @Test
  void hashDecoyPasswordInvokesThePasswordEncoder() {
    CredentialsService serviceWithMockEncoder =
        new CredentialsService(credentials, verificationTokens, mockPasswordEncoder, PROPERTIES);

    serviceWithMockEncoder.hashDecoyPassword();

    verify(mockPasswordEncoder).encode(anyString());
  }

  // Both of this domain's tables, from one call: the caller purging an account must not have to
  // know that proof-of-identity material is split across two of them.
  @Test
  void deleteAllForUserClearsBothTheCredentialAndTheVerificationTokens() {
    UUID userId = UUID.randomUUID();

    service.deleteAllForUser(userId);

    verify(credentials).deleteByUserId(userId);
    verify(verificationTokens).deleteByUserId(userId);
  }

  // The decoy hash has to contend for the same permits every real hash does. If it did not, an
  // enumerated branch would answer immediately while a real hash waited its turn under load —
  // exactly the timing difference hashDecoyPassword() exists to erase, reintroduced by the
  // memory gate. One permit makes "shares the gate" observable: the real hash cannot begin until
  // the decoy releases.
  @Test
  void aRealHashCannotStartWhileTheDecoyHoldsTheOnlyHashingPermit() throws Exception {
    GatedEncoder encoder = new GatedEncoder();
    CredentialsService gated =
        new CredentialsService(
            credentials, verificationTokens, encoder, new CredentialsProperties(Duration.ZERO, 1));
    Thread decoy = startThread(gated::hashDecoyPassword);
    assertThat(encoder.decoyStarted.await(UNBLOCKED_CALL_TIMEOUT.toMillis(), MILLISECONDS))
        .isTrue();

    Thread real = startThread(() -> gated.createPassword(UUID.randomUUID(), CORRECT_PASSWORD));

    assertThat(encoder.realStarted.await(BLOCKED_CALL_GRACE.toMillis(), MILLISECONDS)).isFalse();
    encoder.releaseDecoy.countDown();
    assertThat(encoder.realStarted.await(UNBLOCKED_CALL_TIMEOUT.toMillis(), MILLISECONDS)).isTrue();
    decoy.join();
    real.join();
  }

  private static Thread startThread(Runnable body) {
    Thread thread = new Thread(body);
    thread.start();
    return thread;
  }

  /**
   * Encoder that parks inside the decoy's hash until released, and records when each kind of hash
   * actually began. Distinguishes the two by the value it is handed: only the test passes {@link
   * #CORRECT_PASSWORD}, so anything else is {@code CredentialsService}'s private decoy.
   */
  private static final class GatedEncoder implements PasswordEncoder {

    private final CountDownLatch decoyStarted = new CountDownLatch(1);
    private final CountDownLatch releaseDecoy = new CountDownLatch(1);
    private final CountDownLatch realStarted = new CountDownLatch(1);

    @Override
    public String encode(CharSequence rawPassword) {
      if (CORRECT_PASSWORD.contentEquals(rawPassword)) {
        realStarted.countDown();
        return "real-hash";
      }
      decoyStarted.countDown();
      awaitRelease();
      return "decoy-hash";
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return false;
    }

    private void awaitRelease() {
      try {
        releaseDecoy.await(UNBLOCKED_CALL_TIMEOUT.toMillis(), MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
