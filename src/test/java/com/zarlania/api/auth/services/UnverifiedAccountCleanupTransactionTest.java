package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.zarlania.api.auth.exceptions.AccountVerifiedDuringPurgeException;
import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.testsupport.AccountAssertions;
import com.zarlania.api.testsupport.SeededAccount;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.testsupport.TransactionTestBase;
import com.zarlania.api.users.services.UserService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Where the sweep's transaction boundaries actually are.
 *
 * <p>Both cases here turn on the same design decision: each account is purged in its own
 * transaction, distinct from the one that listed the candidates. That is what lets a single
 * account's failure roll back without taking the sweep with it, and what makes the gap between
 * listing and purging real enough to need a re-check.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class UnverifiedAccountCleanupTransactionTest extends TransactionTestBase {

  private static final Duration EXPIRED_AGE = Duration.ofDays(8);

  // A spy rather than a plain injected bean: the resilience case needs exactly one account's
  // deletion to fail mid-transaction while every other call passes through to the real repository,
  // which only Mockito's spy machinery can do against a live Postgres-backed bean.
  @MockitoSpyBean private RefreshTokenRepository refreshTokens;

  private final UnverifiedAccountCleanup cleanup;
  private final UnverifiedAccountPurger purger;
  private final UserService userService;
  private final TestAccounts accounts;
  private final AccountAssertions accountAssertions;

  // Two things must hold if each account is really purged in its own transaction rather than as a
  // best-effort loop body: the failing account's earlier deletes in that same method — verification
  // token, password credential — must have rolled back rather than stuck half-applied, and the
  // sweep must still finish the other, healthy account rather than aborting outright.
  @Test
  void oneAccountsFailureRollsBackOnlyThatAccountAndDoesNotAbortTheSweep() {
    SeededAccount poisoned = accounts.fullAccount("poisoned-expired", false);
    accounts.backdateCreatedAt(poisoned.userId(), EXPIRED_AGE);
    SeededAccount healthy = accounts.fullAccount("healthy-expired", false);
    accounts.backdateCreatedAt(healthy.userId(), EXPIRED_AGE);
    doThrow(new IllegalStateException("simulated failure"))
        .when(refreshTokens)
        .deleteByUserId(poisoned.userId());

    cleanup.purgeExpiredUnverifiedAccounts();

    accountAssertions.assertFullyIntact(poisoned);
    accountAssertions.assertFullyGone(healthy);
  }

  // The sweep lists its candidates in one transaction and purges each in another, so the listing is
  // always a little stale. A real person whose verification mail sat in spam past the deadline can
  // click their link in that gap, and the purge would then delete a live, verified account.
  //
  // The purger is called directly because that is precisely the state the race produces — an
  // account
  // that was unverified when listed and is verified by the time the purge runs — and reproducing it
  // by interleaving two real transactions would only make this slower and flakier. Everything the
  // purge deletes before reaching the guard has to come back, which is what the intact assertion
  // checks: the rollback is the fix, not the guard on its own.
  @Test
  void anAccountVerifiedAfterTheSweepListedItSurvivesThePurgeCompletelyIntact() {
    SeededAccount account = accounts.fullAccount("verified-mid-sweep", false);
    accounts.backdateCreatedAt(account.userId(), EXPIRED_AGE);
    userService.markEmailVerified(account.userId());

    assertThatThrownBy(() -> purger.purgeOneAccount(account.userId()))
        .isInstanceOf(AccountVerifiedDuringPurgeException.class);

    accountAssertions.assertFullyIntact(account);
  }
}
