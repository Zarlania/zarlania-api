package com.zarlania.api.users.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import com.zarlania.api.users.services.UserService;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries this repository declares itself, against real Postgres.
 *
 * <p>Two things here can only be checked against a real database: that {@code citext} makes lookups
 * case-insensitive, which no in-memory double would reproduce, and that the conditional delete
 * evaluates its condition against the committed row rather than a prior read.
 *
 * <p>Transactional at the class level, which does two things at once. Derived deletes and
 * {@code @Modifying} queries need a transaction to run in at all — in production the calling
 * service supplies one, and there is no service here. And Spring rolls the transaction back after
 * each test, which is what keeps these isolated from every other class sharing the one container.
 */
@Transactional
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class UserRepositoryIntegrationTest extends IntegrationTestBase {

  private static final Duration EXPIRED_AGE = Duration.ofDays(8);

  private final UserRepository users;
  private final UserService userService;
  private final TestAccounts accounts;
  private final Clock clock;

  // citext, not lower(): the column type is what makes these equal, so a lookup written any other
  // way would still pass here while failing on a spelling nobody thought to test.
  @ParameterizedTest(name = "finding by {0}")
  @ValueSource(strings = {"casing@example.com", "CASING@EXAMPLE.COM", "Casing@Example.Com"})
  void findByEmailIsCaseInsensitive(String spelling) {
    accounts.user("casing");

    assertThat(users.findByEmail(spelling)).isPresent();
    assertThat(users.existsByEmail(spelling)).isTrue();
  }

  @ParameterizedTest(name = "finding by {0}")
  @ValueSource(strings = {"namecasing", "NAMECASING", "NameCasing"})
  void findByUsernameIsCaseInsensitive(String spelling) {
    accounts.user("namecasing");

    assertThat(users.findByUsername(spelling)).isPresent();
    assertThat(users.existsByUsername(spelling)).isTrue();
  }

  @Test
  void findByEmailVerifiedAtIsNullAndCreatedAtBeforeListsOnlyStaleUnverifiedAccounts() {
    UserDto stale = accounts.user("repo-stale-unverified");
    accounts.backdateCreatedAt(stale.id(), EXPIRED_AGE);
    UserDto fresh = accounts.user("repo-fresh-unverified");
    UserDto staleButVerified = accounts.user("repo-stale-verified");
    accounts.backdateCreatedAt(staleButVerified.id(), EXPIRED_AGE);
    userService.markEmailVerified(staleButVerified.id());

    var candidates =
        users.findByEmailVerifiedAtIsNullAndCreatedAtBefore(
            clock.instant().minus(Duration.ofDays(7)));

    assertThat(candidates).extracting(user -> user.getId()).contains(stale.id());
    assertThat(candidates)
        .extracting(user -> user.getId())
        .doesNotContain(fresh.id(), staleButVerified.id());
  }

  @Test
  void deleteByIdAndEmailVerifiedAtIsNullDeletesAnUnverifiedAccountAndReportsTheRow() {
    UserDto user = accounts.user("repo-conditional-delete");

    assertThat(users.deleteByIdAndEmailVerifiedAtIsNull(user.id())).isEqualTo(1);
    assertThat(users.findById(user.id())).isEmpty();
  }

  // The row count is the caller's whole signal: a purge that lost the race against a verification
  // has to be able to tell that it deleted nothing, rather than assuming it succeeded.
  @Test
  void deleteByIdAndEmailVerifiedAtIsNullLeavesAVerifiedAccountAloneAndReportsNoRow() {
    UserDto user = accounts.user("repo-conditional-keep");
    userService.markEmailVerified(user.id());

    assertThat(users.deleteByIdAndEmailVerifiedAtIsNull(user.id())).isZero();
    assertThat(users.findById(user.id())).isPresent();
  }
}
