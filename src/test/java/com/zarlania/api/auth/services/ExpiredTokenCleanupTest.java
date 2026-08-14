package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.services.EmailVerificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * That the two sweeps fail independently, which the integration test cannot show: it runs both
 * halves against a healthy database, where nothing throws and the isolation is invisible.
 *
 * <p>Failure isolation is the only thing worth asserting here — a sweep skipped because a different
 * table's sweep failed is a table that quietly stops being pruned, and the free tier's 1 GB is what
 * eventually reports it. What each half actually deletes is the integration test's subject.
 */
@ExtendWith(MockitoExtension.class)
class ExpiredTokenCleanupTest {

  private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");

  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private EmailVerificationService emailVerificationService;

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
  private Logger logger;
  private ExpiredTokenCleanup cleanup;

  @BeforeEach
  void attachAppender() {
    cleanup =
        new ExpiredTokenCleanup(
            refreshTokenRepository, emailVerificationService, Clock.fixed(NOW, ZoneOffset.UTC));
    logger = (Logger) LoggerFactory.getLogger(ExpiredTokenCleanup.class);
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(captured);
    captured.stop();
  }

  @Test
  void prunesBothTablesAndReportsEachCountWhenNothingFails() {
    when(refreshTokenRepository.deleteFamiliesExpiredBefore(NOW)).thenReturn(3);
    when(emailVerificationService.pruneDeadTokens()).thenReturn(5);

    cleanup.pruneDeadTokens();

    assertThat(messages()).contains("Pruned 3 expired refresh tokens");
    assertThat(messages()).contains("Pruned 5 dead verification tokens");
    assertThat(levels()).doesNotContain(Level.ERROR);
  }

  @Test
  void prunesVerificationTokensEvenWhenTheRefreshTokenSweepThrows() {
    when(refreshTokenRepository.deleteFamiliesExpiredBefore(any()))
        .thenThrow(new IllegalStateException("connection reset"));
    when(emailVerificationService.pruneDeadTokens()).thenReturn(5);

    assertThatCode(() -> cleanup.pruneDeadTokens()).doesNotThrowAnyException();

    verify(emailVerificationService).pruneDeadTokens();
    assertThat(messages()).contains("Pruned 5 dead verification tokens");
  }

  @Test
  void prunesRefreshTokensAndKeepsTheirCountWhenTheVerificationSweepThrows() {
    when(refreshTokenRepository.deleteFamiliesExpiredBefore(NOW)).thenReturn(3);
    when(emailVerificationService.pruneDeadTokens())
        .thenThrow(new IllegalStateException("connection reset"));

    assertThatCode(() -> cleanup.pruneDeadTokens()).doesNotThrowAnyException();

    verify(refreshTokenRepository).deleteFamiliesExpiredBefore(NOW);
    // The successful half still reports its count: a failure in the other one must not cost the
    // record of what this one actually did.
    assertThat(messages()).contains("Pruned 3 expired refresh tokens");
  }

  // Absorbed is not the same as hidden. Each failure has to reach the log at ERROR carrying the
  // cause, or a sweep that has been dead for weeks looks exactly like one with nothing to do.
  @Test
  void logsEachFailureAtErrorWithTheCause() {
    IllegalStateException refreshFailure = new IllegalStateException("refresh side");
    IllegalStateException verificationFailure = new IllegalStateException("verification side");
    when(refreshTokenRepository.deleteFamiliesExpiredBefore(any())).thenThrow(refreshFailure);
    when(emailVerificationService.pruneDeadTokens()).thenThrow(verificationFailure);

    cleanup.pruneDeadTokens();

    assertThat(captured.list)
        .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR))
        .hasSize(2)
        .anySatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).contains("expired refresh tokens");
              assertThat(event.getThrowableProxy().getMessage()).isEqualTo("refresh side");
            })
        .anySatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).contains("dead verification tokens");
              assertThat(event.getThrowableProxy().getMessage()).isEqualTo("verification side");
            });
  }

  private List<String> messages() {
    return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private List<Level> levels() {
    return captured.list.stream().map(ILoggingEvent::getLevel).toList();
  }
}
