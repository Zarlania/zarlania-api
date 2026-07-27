package com.zarlania.api.credentials.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.credentials.entities.PasswordCredential;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import java.util.Optional;
import java.util.UUID;
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

  @Mock private PasswordCredentialRepository credentials;

  private CredentialsService service;

  @BeforeEach
  void setUp() {
    service = new CredentialsService(credentials, ENCODER);
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
}
