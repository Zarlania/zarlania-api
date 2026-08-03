package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshToken;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.organizations.dtos.OrganizationDto;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RefreshTokenServiceIntegrationTest extends IntegrationTestBase {

  private static final Duration FAMILY_LIFETIME = Duration.ofDays(30);
  private static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(5);

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

  // The reuse log line is the only trace theft detection leaves anywhere an operator can see —
  // the caller just gets the same 401 as any bad token — so its presence and contents are
  // contract, not implementation detail: the marker is what an alert would match on, and the two
  // ids are what an investigation would pivot on.
  @Test
  void rotatingAnAlreadyUsedTokenLogsATheftSignalNamingTheUserAndFamily() {
    UUID userId = seedUserId("rotate-reuse-log");
    OrganizationDto org = seedPersonalOrganization(userId, "rotate-reuse-log");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());
    refreshTokenService.rotate(issued.raw());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
    try {
      assertThatThrownBy(() -> refreshTokenService.rotate(issued.raw()))
          .isInstanceOf(ReusedRefreshTokenException.class);
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }

    assertThat(captured.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("REFRESH_TOKEN_REUSE")
                  .contains(userId.toString())
                  .contains(familyId.toString());
            });
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
}
