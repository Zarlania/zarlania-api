package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.auth.dtos.IssuedRefreshToken;
import com.zarlania.api.auth.dtos.RefreshRotation;
import com.zarlania.api.auth.entities.RefreshTokenEntity;
import com.zarlania.api.auth.exceptions.InvalidRefreshTokenException;
import com.zarlania.api.auth.exceptions.ReusedRefreshTokenException;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.organizations.dtos.Organization;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.testsupport.TransactionTestBase;
import com.zarlania.api.users.dtos.User;
import com.zarlania.api.users.services.UserService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What two callers racing on one refresh-token family observe of each other.
 *
 * <p>Split out of {@code RefreshTokenServiceIntegrationTest} because the subject is different in
 * kind: these do not assert what rotation produces, they assert that the row lock and the
 * family-scoped advisory lock together leave no interleaving in which a family ends up partly live.
 * They provoke real contention, so they run serially — see {@link TransactionTestBase}.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RefreshTokenServiceTransactionTest extends TransactionTestBase {

  private final RefreshTokenService refreshTokenService;
  private final RefreshTokenRepository refreshTokens;
  private final UserService userService;
  private final OrganizationService organizationService;

  @Test
  void concurrentRotationOfTheSameTokenSucceedsExactlyOnce() throws Exception {
    UUID userId = seedUserId("rotate-race");
    Organization organization = seedPersonalOrganization(userId, "rotate-race");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, organization.id());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    List<Boolean> outcomes =
        raceTwo(() -> rotateSucceeds(issued.raw()), () -> rotateSucceeds(issued.raw()));

    assertThat(outcomes).filteredOn(succeeded -> succeeded).hasSize(1);
    assertThat(outcomes).filteredOn(succeeded -> !succeeded).hasSize(1);
    List<RefreshTokenEntity> family = refreshTokens.findByFamilyId(familyId);
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
    Organization organization = seedPersonalOrganization(userId, "revoke-race");
    IssuedRefreshToken first = refreshTokenService.startFamily(userId, organization.id());
    RefreshRotation second = refreshTokenService.rotate(first.raw());
    UUID familyId = findStoredToken(second.newRaw()).getFamilyId();

    raceTwo(() -> revoke(first.raw()), () -> revoke(second.newRaw()));

    List<RefreshTokenEntity> family = refreshTokens.findByFamilyId(familyId);
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
    Organization organization = seedPersonalOrganization(userId, "revoke-rotate-race");
    IssuedRefreshToken issued = refreshTokenService.startFamily(userId, organization.id());
    UUID familyId = findStoredToken(issued.raw()).getFamilyId();

    raceTwo(() -> rotateTolerantOfLosingTheRace(issued.raw()), () -> revoke(issued.raw()));

    List<RefreshTokenEntity> family = refreshTokens.findByFamilyId(familyId);
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
    } catch (ReusedRefreshTokenException exception) {
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
    } catch (ReusedRefreshTokenException | InvalidRefreshTokenException exception) {
      // Expected under either race ordering; see the caller's test comment.
    }
    return null;
  }

  private UUID seedUserId(String slug) {
    User user = userService.createUnverified(slug + "@example.com", slug);
    return user.id();
  }

  private Organization seedPersonalOrganization(UUID userId, String slug) {
    return organizationService.createPersonalOrganization(userId, slug + "'s Space");
  }

  private RefreshTokenEntity findStoredToken(String raw) {
    return refreshTokens.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow();
  }
}
