package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zarlania.api.auth.AuthProperties;
import com.zarlania.api.auth.exceptions.AccountVerifiedDuringPurgeException;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * What the sweep reports about itself, which the integration test cannot show: it purges against a
 * healthy database, where the purge never fails and every branch below the success one is
 * unreachable.
 *
 * <p>The subject is the log, not the deletes — what each purge removes belongs to {@code
 * UnverifiedAccountCleanupIntegrationTest}. A sweep is unattended, so its log is the only account
 * anyone gets of what it did, and a success line that outlives a rolled-back purge is worse than no
 * line at all.
 */
@ExtendWith(MockitoExtension.class)
class UnverifiedAccountCleanupTest {

  private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
  private static final Duration MAX_AGE = Duration.ofDays(7);
  private static final String PURGED_MESSAGE_FRAGMENT = "Purged unverified account";

  @Mock private UserService userService;
  @Mock private UnverifiedAccountPurger unverifiedAccountPurger;

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
  private final ListAppender<ILoggingEvent> capturedFromPurger = new ListAppender<>();
  private Logger logger;
  private Logger purgerLogger;
  private UnverifiedAccountCleanup cleanup;

  @BeforeEach
  void attachAppenders() {
    cleanup =
        new UnverifiedAccountCleanup(
            userService,
            unverifiedAccountPurger,
            authProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    logger = (Logger) LoggerFactory.getLogger(UnverifiedAccountCleanup.class);
    purgerLogger = (Logger) LoggerFactory.getLogger(UnverifiedAccountPurger.class);
    logger.setLevel(Level.DEBUG);
    captured.start();
    capturedFromPurger.start();
    logger.addAppender(captured);
    purgerLogger.addAppender(capturedFromPurger);
  }

  @AfterEach
  void detachAppenders() {
    logger.detachAppender(captured);
    purgerLogger.detachAppender(capturedFromPurger);
    logger.setLevel(null);
    captured.stop();
    capturedFromPurger.stop();
  }

  // The success line has to come from out here rather than from inside purgeOneAccount, because
  // that method is @Transactional: a line it writes is written while the transaction is still
  // open, so a commit that then failed would leave a permanent record of a purge that never
  // happened. Emitting it only once the call has returned is what ties it to a committed purge.
  @Test
  void reportsAPurgeOnlyOnceTheTransactionalCallHasReturned() {
    UUID userId = seedOneExpiredAccount();

    cleanup.purgeExpiredUnverifiedAccounts();

    verify(unverifiedAccountPurger).purgeOneAccount(userId);
    assertThat(messages()).anyMatch(message -> message.contains(PURGED_MESSAGE_FRAGMENT));
    assertThat(messages()).anyMatch(message -> message.contains(userId.toString()));
    assertThat(capturedFromPurger.list)
        .describedAs("the transactional bean must not report an outcome it cannot yet know")
        .isEmpty();
  }

  // The case the move exists for: the purge threw, so nothing was committed, and a success line
  // here would be a claim that an account is gone while its rows are all still present.
  @Test
  void reportsNoPurgeWhenTheTransactionalCallThrows() {
    UUID userId = seedOneExpiredAccount();
    doThrow(new IllegalStateException("connection reset"))
        .when(unverifiedAccountPurger)
        .purgeOneAccount(userId);

    cleanup.purgeExpiredUnverifiedAccounts();

    assertThat(messages()).noneMatch(message -> message.contains(PURGED_MESSAGE_FRAGMENT));
    assertThat(captured.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage()).contains(userId.toString());
              assertThat(event.getThrowableProxy().getMessage()).isEqualTo("connection reset");
            });
  }

  // An account verified between the listing and the purge is a routine, self-correcting race that
  // rolled itself back, so it is neither a purge to report nor a failure to page anyone about.
  @Test
  void reportsAMidSweepVerificationAtDebugAndNotAsAPurge() {
    UUID userId = seedOneExpiredAccount();
    doThrow(AccountVerifiedDuringPurgeException.forUser(userId))
        .when(unverifiedAccountPurger)
        .purgeOneAccount(userId);

    cleanup.purgeExpiredUnverifiedAccounts();

    assertThat(messages()).noneMatch(message -> message.contains(PURGED_MESSAGE_FRAGMENT));
    assertThat(captured.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage()).contains("verified mid-sweep");
            });
  }

  // One bad row must not cost the rest of the pass: the accounts after it still need purging, and
  // each one that succeeds still has to be reported.
  @Test
  void purgesAndReportsLaterAccountsAfterAnEarlierOneFails() {
    UUID failing = UUID.randomUUID();
    UUID succeeding = UUID.randomUUID();
    when(userService.findUnverifiedOlderThan(NOW.minus(MAX_AGE)))
        .thenReturn(List.of(user(failing), user(succeeding)));
    doThrow(new IllegalStateException("connection reset"))
        .when(unverifiedAccountPurger)
        .purgeOneAccount(failing);

    assertThatCode(() -> cleanup.purgeExpiredUnverifiedAccounts()).doesNotThrowAnyException();

    verify(unverifiedAccountPurger).purgeOneAccount(succeeding);
    assertThat(messages())
        .anyMatch(
            message ->
                message.contains(PURGED_MESSAGE_FRAGMENT)
                    && message.contains(succeeding.toString()));
    assertThat(messages())
        .noneMatch(
            message ->
                message.contains(PURGED_MESSAGE_FRAGMENT) && message.contains(failing.toString()));
  }

  private UUID seedOneExpiredAccount() {
    UUID userId = UUID.randomUUID();
    when(userService.findUnverifiedOlderThan(NOW.minus(MAX_AGE))).thenReturn(List.of(user(userId)));
    return userId;
  }

  private List<String> messages() {
    return captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static User user(UUID userId) {
    return new User(userId, "purge-" + userId + "@zarlania.test", "purge-" + userId, false);
  }

  private static AuthProperties authProperties() {
    return new AuthProperties(
        "https://api.zarlania.test",
        Duration.ofMinutes(15),
        Duration.ofDays(30),
        MAX_AGE,
        true,
        "",
        "",
        "https://zarlania.com");
  }
}
