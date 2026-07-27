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
import java.util.List;
import java.util.UUID;
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

  @Test
  void startFamilyPersistsAHashedTokenWithAThirtyDayExpiry() {
    UUID userId = seedUserId("family-start");
    OrganizationDto org = seedPersonalOrganization(userId, "family-start");

    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, org.id());

    RefreshToken stored =
        refreshTokens.findByTokenHash(TokenHasher.sha256Hex(issued.raw())).orElseThrow();
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
    RefreshToken original =
        refreshTokens.findByTokenHash(TokenHasher.sha256Hex(issued.raw())).orElseThrow();

    RefreshRotation rotation = refreshTokenService.rotate(issued.raw());

    assertThat(rotation.newRaw()).isNotEqualTo(issued.raw());
    assertThat(rotation.userId()).isEqualTo(userId);
    assertThat(rotation.organizationId()).isEqualTo(org.id());
    assertThat(rotation.familyExpiresAt()).isEqualTo(original.getFamilyExpiresAt());

    RefreshToken oldRow = refreshTokens.findById(original.getId()).orElseThrow();
    assertThat(oldRow.getUsedAt()).isNotNull();

    RefreshToken newRow =
        refreshTokens.findByTokenHash(TokenHasher.sha256Hex(rotation.newRaw())).orElseThrow();
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
    UUID familyId =
        refreshTokens
            .findByTokenHash(TokenHasher.sha256Hex(issued.raw()))
            .orElseThrow()
            .getFamilyId();

    assertThatThrownBy(() -> refreshTokenService.rotate(issued.raw()))
        .isInstanceOf(ReusedRefreshTokenException.class);

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).hasSize(2);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
    RefreshToken newestRow =
        refreshTokens.findByTokenHash(TokenHasher.sha256Hex(firstRotation.newRaw())).orElseThrow();
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
    UUID familyId =
        refreshTokens
            .findByTokenHash(TokenHasher.sha256Hex(issued.raw()))
            .orElseThrow()
            .getFamilyId();

    refreshTokenService.revokeFamilyOf(rotation.newRaw());

    List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
    assertThat(family).hasSize(2);
    assertThat(family).allSatisfy(row -> assertThat(row.getRevokedAt()).isNotNull());
  }
}
