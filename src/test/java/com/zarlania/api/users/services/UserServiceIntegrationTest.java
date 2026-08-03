package com.zarlania.api.users.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.testsupport.IntegrationTestBase;
import com.zarlania.api.testsupport.TestAccounts;
import com.zarlania.api.users.dtos.UserDto;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What the users domain does against a real database, as opposed to what it decides.
 *
 * <p>Everything here needs Postgres to mean anything: the {@code citext} columns decide which
 * spellings are the same account, the unique constraints — not a prior check — are what actually
 * refuse a duplicate, and a stamped timestamp is only trustworthy once it has survived a round trip
 * through a {@code timestamptz(6)} column. {@code UserServiceTest} covers the mapping and
 * delegation around all of that.
 */
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class UserServiceIntegrationTest extends IntegrationTestBase {

  private final UserService userService;
  private final TestAccounts accounts;
  private final Clock clock;

  @Test
  void createUnverifiedPersistsAnAccountThatIsNotYetVerified() {
    UserDto created = userService.createUnverified("newcomer@example.com", "newcomer");

    assertThat(created.emailVerified()).isFalse();
    assertThat(userService.findById(created.id()))
        .get()
        .extracting(UserDto::username)
        .isEqualTo("newcomer");
  }

  // The constraint is the enforcement, not the prior existence check: two registrations can pass
  // the check and only one can pass this, which is why the service has to handle losing.
  @Test
  void theDatabaseRefusesADuplicateAddressEvenInADifferentSpelling() {
    accounts.user("dupeaddress");

    assertThatThrownBy(() -> userService.createUnverified("DUPEADDRESS@EXAMPLE.COM", "someoneelse"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theDatabaseRefusesADuplicateUsernameEvenInADifferentSpelling() {
    accounts.user("dupename");

    assertThatThrownBy(() -> userService.createUnverified("other@example.com", "DupeName"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // Both columns are citext, so one account answers to every spelling of either identifier — the
  // property that lets the throttle key on a normalised form and still bucket correctly.
  // Each case seeds its own account, since the database is shared for the run and one slug cannot
  // be created four times.
  @ParameterizedTest(name = "found by {1}")
  @CsvSource({
    "findername, findername",
    "findercase, FINDERCASE",
    "findermail, findermail@example.com",
    "findermailcase, FINDERMAILCASE@EXAMPLE.COM"
  })
  void findByIdentifierMatchesEitherColumnCaseInsensitively(String slug, String identifier) {
    UserDto created = accounts.user(slug);

    assertThat(userService.findByIdentifier(identifier))
        .get()
        .extracting(UserDto::id)
        .isEqualTo(created.id());
  }

  @Test
  void findByIdentifierIsEmptyForSomethingThatMatchesNeitherColumn() {
    assertThat(userService.findByIdentifier("nobody-at-all")).isEmpty();
  }

  @Test
  void markEmailVerifiedStampsTheRowSoTheChangeSurvivesAReload() {
    UserDto created = accounts.user("verifier");

    userService.markEmailVerified(created.id());

    assertThat(userService.findById(created.id()))
        .get()
        .extracting(UserDto::emailVerified)
        .isEqualTo(true);
  }

  @Test
  void findUnverifiedOlderThanReturnsTheStaleUnverifiedAndNothingElse() {
    UserDto stale = accounts.user("service-stale");
    accounts.backdateCreatedAt(stale.id(), Duration.ofDays(8));
    UserDto fresh = accounts.user("service-fresh");

    var candidates = userService.findUnverifiedOlderThan(clock.instant().minus(Duration.ofDays(7)));

    assertThat(candidates).extracting(UserDto::id).contains(stale.id()).doesNotContain(fresh.id());
  }

  // The row count the repository reports is the caller's only way to know it lost the race against
  // a verification, so it has to reflect what actually happened rather than what was attempted.
  @Test
  void deleteIfStillUnverifiedReportsWhetherARowActuallyWent() {
    UserDto doomed = accounts.user("service-doomed");
    UserDto saved = accounts.user("service-saved");
    userService.markEmailVerified(saved.id());

    assertThat(userService.deleteIfStillUnverified(doomed.id())).isTrue();
    assertThat(userService.deleteIfStillUnverified(saved.id())).isFalse();
    assertThat(userService.findById(doomed.id())).isEmpty();
    assertThat(userService.findById(saved.id())).isPresent();
  }
}
