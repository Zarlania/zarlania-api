package com.zarlania.api.credentials.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries this repository declares itself, against real Postgres.
 *
 * <p>The pruning query is the one worth pinning: its selectivity is stated in the method name and
 * nowhere else, and getting it wrong in either direction is silent — too broad deletes an
 * outstanding link a person is about to click, too narrow lets dead rows accumulate forever.
 *
 * <p>Transactional at the class level, which does two things at once. Derived deletes and
 * {@code @Modifying} queries need a transaction to run in at all — in production the calling
 * service supplies one, and there is no service here. And Spring rolls the transaction back after
 * each test, which is what keeps these isolated from every other class sharing the one container.
 */
@Transactional
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class EmailVerificationTokenRepositoryIntegrationTest extends IntegrationTestBase {

  private final EmailVerificationTokenRepository verificationTokens;
  private final TestAccounts accounts;
  private final Clock clock;

  @Test
  void findByTokenHashFindsATokenByItsHashAndNothingByAnUnknownOne() {
    UserDto user = accounts.user("token-repo-find");
    String hash = accounts.verificationToken(user.id(), farFuture(), false);

    assertThat(verificationTokens.findByTokenHash(hash)).isPresent();
    assertThat(verificationTokens.findByTokenHash("0".repeat(64))).isEmpty();
  }

  @Test
  void deleteByUserIdAndConsumedAtIsNullClearsOutstandingTokensAndKeepsConsumedOnes() {
    UserDto user = accounts.user("token-repo-outstanding");
    String outstanding = accounts.verificationToken(user.id(), farFuture(), false);
    String consumed = accounts.verificationToken(user.id(), farFuture(), true);

    verificationTokens.deleteByUserIdAndConsumedAtIsNull(user.id());

    assertThat(verificationTokens.findByTokenHash(outstanding)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(consumed)).isPresent();
  }

  // Consumed rows go regardless of age, expired rows go by the cutoff, and an outstanding unexpired
  // token survives both clauses — which is the whole of what the method name promises.
  @Test
  void deleteConsumedTokensAndThoseExpiredBeforeRemovesOnlyWhatCanNeverBeReadAgain() {
    UserDto user = accounts.user("token-repo-prune");
    String consumedButFresh = accounts.verificationToken(user.id(), farFuture(), true);
    String expired = accounts.verificationToken(user.id(), clock.instant().minusSeconds(1), false);
    String outstanding = accounts.verificationToken(user.id(), farFuture(), false);

    int deleted = verificationTokens.deleteConsumedTokensAndThoseExpiredBefore(clock.instant());

    assertThat(deleted).isGreaterThanOrEqualTo(2);
    assertThat(verificationTokens.findByTokenHash(consumedButFresh)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(expired)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(outstanding)).isPresent();
  }

  @Test
  void deleteByUserIdClearsEveryTokenTheAccountHoldsWhateverItsState() {
    UserDto user = accounts.user("token-repo-delete-all");
    String outstanding = accounts.verificationToken(user.id(), farFuture(), false);
    String consumed = accounts.verificationToken(user.id(), farFuture(), true);

    verificationTokens.deleteByUserId(user.id());

    assertThat(verificationTokens.findByTokenHash(outstanding)).isEmpty();
    assertThat(verificationTokens.findByTokenHash(consumed)).isEmpty();
  }

  private Instant farFuture() {
    return clock.instant().plus(Duration.ofDays(1));
  }
}
