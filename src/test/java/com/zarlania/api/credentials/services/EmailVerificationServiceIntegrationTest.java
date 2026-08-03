package com.zarlania.api.credentials.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.security.TokenHasher;
import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two promises the verification tokens make, against a real database: issuing a fresh token
 * invalidates every outstanding one, and a token can be redeemed exactly once.
 *
 * <p>Both are statements about rows, not about calls, so a mocked repository cannot show either.
 * What two concurrent redemptions observe of each other is a different question again, and belongs
 * to the transaction tier.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class EmailVerificationServiceIntegrationTest extends IntegrationTestBase {

  private final EmailVerificationService emailVerificationService;
  private final EmailVerificationTokenRepository verificationTokens;
  private final TestAccounts accounts;

  @Test
  void issuingStoresOnlyTheHashOfTheTokenItHandsBack() {
    UserDto user = accounts.user("verify-hash");

    String raw = emailVerificationService.issue(user.id());

    assertThat(verificationTokens.findByTokenHash(TokenHasher.sha256Hex(raw))).isPresent();
    assertThat(verificationTokens.findByTokenHash(raw)).isEmpty();
  }

  @Test
  void consumingAUsableTokenReturnsTheAccountItVerifies() {
    UserDto user = accounts.user("verify-consume");
    String raw = emailVerificationService.issue(user.id());

    assertThat(emailVerificationService.consume(raw)).contains(user.id());
  }

  @Test
  void aTokenCanBeConsumedOnlyOnce() {
    UserDto user = accounts.user("verify-once");
    String raw = emailVerificationService.issue(user.id());

    assertThat(emailVerificationService.consume(raw)).isPresent();
    assertThat(emailVerificationService.consume(raw)).isEmpty();
  }

  // Otherwise a leaked earlier email would stay redeemable for as long as it had left to run, which
  // defeats the point of letting someone ask for a fresh link.
  @Test
  void issuingAFreshTokenKillsTheOutstandingOne() {
    UserDto user = accounts.user("verify-supersede");
    String first = emailVerificationService.issue(user.id());

    String second = emailVerificationService.issue(user.id());

    assertThat(emailVerificationService.consume(first)).isEmpty();
    assertThat(emailVerificationService.consume(second)).contains(user.id());
  }

  // Only the account's own outstanding tokens: issuing for one account must not disturb another's.
  @Test
  void issuingForOneAccountLeavesAnotherAccountsTokenAlone() {
    UserDto mine = accounts.user("verify-mine");
    UserDto theirs = accounts.user("verify-theirs");
    String theirToken = emailVerificationService.issue(theirs.id());

    emailVerificationService.issue(mine.id());

    assertThat(emailVerificationService.consume(theirToken)).contains(theirs.id());
  }

  @Test
  void consumingAnUnknownTokenIsEmptyRatherThanAnError() {
    assertThat(emailVerificationService.consume("nobody-issued-this")).isEmpty();
  }

  @Test
  void pruningRemovesConsumedTokensAndLeavesOutstandingOnes() {
    UserDto consumedOwner = accounts.user("verify-prune-consumed");
    String consumed = emailVerificationService.issue(consumedOwner.id());
    emailVerificationService.consume(consumed);
    UserDto outstandingOwner = accounts.user("verify-prune-outstanding");
    String outstanding = emailVerificationService.issue(outstandingOwner.id());

    emailVerificationService.pruneDeadTokens();

    assertThat(verificationTokens.findByTokenHash(TokenHasher.sha256Hex(consumed))).isEmpty();
    assertThat(verificationTokens.findByTokenHash(TokenHasher.sha256Hex(outstanding))).isPresent();
  }
}
