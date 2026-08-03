package com.zarlania.api.auth.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.testsupport.TestAccounts.SeededAccount;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries this repository declares itself, against real Postgres.
 *
 * <p>Only the ones with something to get wrong: a projection whose whole purpose is to stay out of
 * the persistence context, an ordering that exists to prevent deadlock, and a delete whose
 * selectivity decides whether theft detection still works afterwards. The inherited CRUD methods
 * are Spring Data's to test, not this project's.
 *
 * <p>Transactional at the class level, which does two things at once. Derived deletes and
 * {@code @Modifying} queries need a transaction to run in at all — in production the calling
 * service supplies one, and there is no service here. And Spring rolls the transaction back after
 * each test, which is what keeps these isolated from every other class sharing the one container.
 */
@Transactional
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class RefreshTokenRepositoryIntegrationTest extends IntegrationTestBase {

  private final RefreshTokenRepository refreshTokens;
  private final TestAccounts accounts;
  private final Clock clock;

  @Test
  void findFamilyIdByTokenHashResolvesTheFamilyWithoutLoadingTheToken() {
    SeededAccount account = accounts.fullAccount("repo-family-id", false);

    UUID familyId = refreshTokens.findFamilyIdByTokenHash(account.refreshTokenHash()).orElseThrow();

    assertThat(refreshTokens.findByFamilyId(familyId))
        .singleElement()
        .extracting(token -> token.getTokenHash())
        .isEqualTo(account.refreshTokenHash());
  }

  @Test
  void findFamilyIdByTokenHashIsEmptyForAHashNothingWasEverIssuedFor() {
    assertThat(refreshTokens.findFamilyIdByTokenHash("0".repeat(64))).isEmpty();
  }

  // The ascending order is not cosmetic: it is what makes two callers locking one family request
  // its rows in the same sequence, so they can never form an AB-BA wait cycle.
  @Test
  void findByFamilyIdOrderByIdReturnsEveryRowOfTheFamilyInAscendingIdOrder() {
    SeededAccount account = accounts.fullAccount("repo-family-order", false);
    UUID familyId = refreshTokens.findFamilyIdByTokenHash(account.refreshTokenHash()).orElseThrow();
    accounts.refreshToken(account.userId(), account.organizationId(), farFuture());
    accounts.refreshToken(account.userId(), account.organizationId(), farFuture());

    List<UUID> ids =
        refreshTokens.findByFamilyIdOrderById(familyId).stream().map(t -> t.getId()).toList();

    assertThat(ids).isSorted();
  }

  // Whole families, and only once past their absolute expiry — deliberately not "used or revoked".
  // A used token has to stay readable until its family dies, because presenting one a second time
  // is exactly what proves theft; deleting it sooner would turn a replay into an ordinary
  // unknown-token 401 and lose the detection entirely.
  @Test
  void deleteFamiliesExpiredBeforeRemovesTheExpiredAndKeepsUsedButLiveOnes() {
    SeededAccount account = accounts.fullAccount("repo-prune", false);
    String expiredHash =
        accounts.refreshToken(
            account.userId(), account.organizationId(), clock.instant().minusSeconds(1));
    String liveHash =
        accounts.refreshToken(account.userId(), account.organizationId(), farFuture());
    refreshTokens.findByTokenHash(liveHash).orElseThrow().markUsed(clock.instant());

    refreshTokens.deleteFamiliesExpiredBefore(clock.instant());

    assertThat(refreshTokens.findByTokenHash(expiredHash)).isEmpty();
    assertThat(refreshTokens.findByTokenHash(liveHash)).isPresent();
  }

  // refresh_tokens carries real foreign keys to both users and organizations, so a purge cannot
  // reach the user row until this has cleared everything pointing at it.
  @Test
  void deleteByUserIdClearsEveryTokenTheAccountHolds() {
    SeededAccount account = accounts.fullAccount("repo-delete-by-user", false);
    accounts.refreshToken(account.userId(), account.organizationId(), farFuture());

    refreshTokens.deleteByUserId(account.userId());

    assertThat(refreshTokens.findByTokenHash(account.refreshTokenHash())).isEmpty();
  }

  private java.time.Instant farFuture() {
    return clock.instant().plus(Duration.ofDays(30));
  }
}
