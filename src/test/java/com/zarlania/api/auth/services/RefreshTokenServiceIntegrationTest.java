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
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
  private final PlatformTransactionManager transactionManager;

  private UUID seedUserId(String slug) {
    UserDto user = userService.createUnverified(slug + "@example.com", slug);
    return user.id();
  }

  private OrganizationDto seedPersonalOrganization(UUID userId, String slug) {
    return organizationService.createPersonalOrganization(userId, slug + "'s Space");
  }

  // findByTokenHash takes a PESSIMISTIC_WRITE lock (see RefreshTokenRepository), which Hibernate
  // only allows inside an active transaction. Test methods aren't transactional, so reads made
  // directly for assertions need their own short-lived transaction to open and close around them.
  private RefreshToken findStoredToken(String raw) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    return template.execute(
        status -> refreshTokens.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow());
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
  // the same instant must not both succeed. RefreshTokenRepository.findByTokenHash takes a
  // PESSIMISTIC_WRITE lock, so the loser blocks until the winner commits, then re-reads usedAt
  // as already set and correctly takes the reuse path instead of also succeeding.
  @Test
  void concurrentRotationOfTheSameTokenSucceedsExactlyOnce() throws Exception {
    UUID userId = seedUserId("rotate-race");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-race");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    List<Boolean> outcomes = raceTwoRotationsOf(issued.raw());

    assertThat(outcomes).filteredOn(succeeded -> succeeded).hasSize(1);
    assertThat(outcomes).filteredOn(succeeded -> !succeeded).hasSize(1);
    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }

  /** Releases two threads at the same instant to call {@code rotate} on the same raw token. */
  private List<Boolean> raceTwoRotationsOf(String raw) throws Exception {
    int racers = 2;
    ExecutorService executor = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> racer =
        () -> {
          ready.countDown();
          start.await();
          return rotateSucceeds(raw);
        };
    try {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(executor.submit(racer));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Boolean> outcomes = new ArrayList<>();
      for (Future<Boolean> future : futures) {
        outcomes.add(future.get(10, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      executor.shutdown();
    }
  }

  private boolean rotateSucceeds(String raw) {
    try {
      refreshTokenService.rotate(raw);
      return true;
    } catch (ReusedRefreshTokenException e) {
      return false;
    }
  }
}
