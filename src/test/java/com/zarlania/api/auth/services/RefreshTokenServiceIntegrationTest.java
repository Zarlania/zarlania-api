package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.common.security.TokenHasher;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.testsupport.PostgresTestContainer;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RefreshTokenServiceIntegrationTest {

  private static final Duration FAMILY_LIFETIME = Duration.ofDays(30);
  private static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(5);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

  private final RefreshTokenService refreshTokenService;
  private final RefreshTokenRepository refreshTokens;
  private final UserService userService;
  private final OrganizationService organizationService;
  private final Clock clock;

  private UUID seedUserId(String slug) {
    UserDto user = userService.createUnverified(slug + "@example.com", slug);
    return user.id();
  }

  private OrganizationDto seedPersonalOrganization(UUID userId, String slug) {
    return organizationService.createPersonalOrganization(userId, slug + "'s Space");
  }

  private RefreshToken findStoredToken(String raw) {
    return refreshTokens.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow();
  }

  @Test
  void startFamilyPersistsAHashedTokenWithAThirtyDayExpiry() {
    UUID userId = seedUserId("family-start");
    OrganizationDto org = seedPersonalOrganization(userId, "family-start");

    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());

    RefreshToken stored = findStoredToken(issued.raw());
    assertThat(stored.getTokenHash()).isNotEqualTo(issued.raw());
    assertThat(stored.getUserId()).isEqualTo(userId);
    assertThat(stored.getOrganizationId()).isEqualTo(org.id());
    assertThat(stored.getFamilyExpiresAt())
        .isCloseTo(clock.instant().plus(FAMILY_LIFETIME), within(CLOCK_TOLERANCE));
    assertThat(issued.familyExpiresAt()).isEqualTo(stored.getFamilyExpiresAt());
  }

  @Test
  void rotateReturnsANewRawMarksTheOldRowUsedAndSharesFamilyMetadata() {
    UUID userId = seedUserId("rotate-happy");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-happy");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    RefreshToken original = findStoredToken(issued.raw());

    RefreshRotation rotation = refreshTokenService.rotate(issued.raw());

    assertThat(rotation.newRaw()).isNotEqualTo(issued.raw());
    assertThat(rotation.userId()).isEqualTo(userId);
    assertThat(rotation.organizationId()).isEqualTo(org.id());
    assertThat(rotation.familyExpiresAt()).isEqualTo(original.getFamilyExpiresAt());

    RefreshToken oldRow = refreshTokens.findById(original.getId()).orElseThrow();
    assertThat(oldRow.getUsedAt()).isNotNull();

    RefreshToken newRow = findStoredToken(rotation.newRaw());
    assertThat(newRow.getFamilyId()).isEqualTo(original.getFamilyId());
    assertThat(newRow.getFamilyExpiresAt()).isEqualTo(original.getFamilyExpiresAt());
    assertThat(newRow.getUsedAt()).isNull();
    assertThat(newRow.getRevokedAt()).isNull();
  }

  @Test
  void rotatingAnAlreadyUsedTokenRevokesEveryRowInTheFamilyAndThrows() {
    UUID userId = seedUserId("rotate-reuse");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-reuse");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    RefreshRotation firstRotation = refreshTokenService.rotate(issued.raw());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    assertThatThrownBy(() -> refreshTokenService.rotate(issued.raw()))
        .isInstanceOf(ReusedRefreshTokenException.class);

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).hasSize(2);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
    RefreshToken newestRow = findStoredToken(firstRotation.newRaw());
    assertThat(newestRow.getRevokedAt()).isNotNull();
  }

  @Test
  void rotatingAnUnknownTokenThrowsInvalid() {
    assertThatThrownBy(() -> refreshTokenService.rotate("not-a-real-token"))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rotatingAFamilyPastItsLifetimeThrowsInvalid() {
    UUID userId = seedUserId("rotate-expired");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-expired");
    String raw = TokenHasher.newUrlSafeToken();
    RefreshToken expired =
        new RefreshToken(
            UUID.randomUUID(),
            userId,
            org.id(),
            TokenHasher.sha256Hex(raw),
            clock.instant().minus(Duration.ofDays(1)));
    refreshTokens.saveAndFlush(expired);

    assertThatThrownBy(() -> refreshTokenService.rotate(raw))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void revokeFamilyOfIsANoOpForAnUnknownToken() {
    refreshTokenService.revokeFamilyOf("not-a-real-token");
  }

  @Test
  void revokeFamilyOfThenRotateThrowsInvalid() {
    UUID userId = seedUserId("revoke-logout");
    OrganizationDto org = seedPersonalOrganization(userId, "revoke-logout");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());

    refreshTokenService.revokeFamilyOf(issued.raw());

    assertThatThrownBy(() -> refreshTokenService.rotate(issued.raw()))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void revokeFamilyOfRevokesEveryUnrevokedRowInTheFamily() {
    UUID userId = seedUserId("revoke-family");
    OrganizationDto org = seedPersonalOrganization(userId, "revoke-family");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    RefreshRotation rotation = refreshTokenService.rotate(issued.raw());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    refreshTokenService.revokeFamilyOf(rotation.newRaw());

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).hasSize(2);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  // Guards against a check-then-act race: two callers redeeming the same not-yet-used token at
  // the same instant must not both succeed. findByFamilyIdOrderById takes a PESSIMISTIC_WRITE
  // lock on the whole family, so the loser blocks until the winner commits, then re-reads usedAt
  // as already set and correctly takes the reuse path instead of also succeeding.
  @Test
  void concurrentRotationOfTheSameTokenSucceedsExactlyOnce() throws Exception {
    UUID userId = seedUserId("rotate-race");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-race");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    List<Boolean> outcomes =
        raceTwo(() -> rotateSucceeds(issued.raw()), () -> rotateSucceeds(issued.raw()));

    assertThat(outcomes).filteredOn(succeeded -> succeeded).hasSize(1);
    assertThat(outcomes).filteredOn(succeeded -> !succeeded).hasSize(1);
    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  // Guards against the deadlock that a naive per-row lock would allow: two callers revoking
  // *different* rows of the *same* family at the same instant must both complete rather than
  // one aborting with a Postgres "deadlock detected". findByFamilyIdOrderById locks every row of
  // a family in the same ascending-id order regardless of which row's raw token started the
  // call, so the two callers always contend for the family's rows in the same sequence and can
  // never form an AB-BA cycle (one holding row X while waiting on row Y, the other holding Y
  // while waiting on X).
  @Test
  void concurrentRevocationOfDifferentTokensInTheSameFamilyDoesNotDeadlock() throws Exception {
    UUID userId = seedUserId("revoke-race");
    OrganizationDto org = seedPersonalOrganization(userId, "revoke-race");
    IssuedRefreshToken first = refreshTokenService.startFamily(userId, org.id());
    RefreshRotation second = refreshTokenService.rotate(first.raw());
    UUID familyId = findStoredToken(second.newRaw()).getFamilyId();

    raceTwo(() -> revoke(first.raw()), () -> revoke(second.newRaw()));

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).hasSize(2);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  // Guards against the gap a per-row lock alone cannot close: if revokeFamilyOf's own locked
  // family read is the one that blocks (waiting on a row an in-flight rotate() holds), Postgres
  // resolves that wait by refreshing only the row it was already waiting on — it does not
  // discover the successor row rotate() inserts as part of the same transaction. A family-scoped
  // advisory lock, taken before either call reads any row, rules this out by fully serializing
  // the two calls: whichever wins runs to completion (commit or throw) before the other starts
  // its own row work, so there is no partial interleaving left to expose. Racing revokeFamilyOf
  // and rotate() on the very same token exercises both possible outcomes: rotate-then-revoke
  // (both the original and its successor end up revoked) and revoke-then-rotate (the token is
  // already revoked, so rotate() throws InvalidRefreshTokenException and no successor is ever
  // minted) — either way, nothing in the family is left live.
  @Test
  void concurrentRevocationAndRotationOfTheSameTokenLeavesNoRowUnrevoked() throws Exception {
    UUID userId = seedUserId("revoke-rotate-race");
    OrganizationDto org = seedPersonalOrganization(userId, "revoke-rotate-race");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    raceTwo(() -> rotateTolerantOfLosingTheRace(issued.raw()), () -> revoke(issued.raw()));

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).isNotEmpty();
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  private Void revoke(String raw) {
    refreshTokenService.revokeFamilyOf(raw);
    return null;
  }

  private boolean rotateSucceeds(String raw) {
    try {
      refreshTokenService.rotate(raw);
      return true;
    } catch (ReusedRefreshTokenException e) {
      return false;
    }
  }

  // Unlike rotateSucceeds, this tolerates InvalidRefreshTokenException too: racing against a
  // concurrent revokeFamilyOf, rotate() throwing that (because the family lock made the logout
  // land first, so the token was already revoked) is exactly as valid an outcome as it winning
  // the race and succeeding — the test asserts on the resulting family state, not on which of
  // the two calls "won".
  private Void rotateTolerantOfLosingTheRace(String raw) {
    try {
      refreshTokenService.rotate(raw);
    } catch (ReusedRefreshTokenException | InvalidRefreshTokenException e) {
      // Expected under either race ordering; see the caller's test comment.
    }
    return null;
  }

  /**
   * Releases two callables at the same instant on separate threads and returns both results (as a
   * fixed two-element list; {@code Arrays.asList} rather than {@code List.of} since a callable
   * result may legitimately be {@code null}). Neither exception nor timeout is swallowed: either
   * fails the test, which is exactly what a deadlock (surfaced as {@code
   * CannotAcquireLockException}) or a genuine hang must do.
   */
  private <T> List<T> raceTwo(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<T> futureFirst = executor.submit(gatedBy(ready, start, first));
      Future<T> futureSecond = executor.submit(gatedBy(ready, start, second));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      T resultFirst = futureFirst.get(10, TimeUnit.SECONDS);
      T resultSecond = futureSecond.get(10, TimeUnit.SECONDS);
      return Arrays.asList(resultFirst, resultSecond);
    } finally {
      executor.shutdown();
    }
  }

  private <T> Callable<T> gatedBy(CountDownLatch ready, CountDownLatch start, Callable<T> task) {
    return () -> {
      ready.countDown();
      start.await();
      return task.call();
    };
  }
}
