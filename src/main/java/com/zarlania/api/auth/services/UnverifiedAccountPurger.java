package com.zarlania.api.auth.services;

import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.services.CredentialsService;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.services.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes one expired, unverified account's rows in FK-safe order, inside a single transaction.
 * Split out from {@link UnverifiedAccountCleanup} rather than made a second method there: a
 * {@code @Scheduled} method calling a {@code @Transactional} method on itself ("self-invocation")
 * bypasses Spring's proxy entirely and would run with no transaction at all. As a separate bean,
 * {@link UnverifiedAccountCleanup} calls {@link #purgeOneAccount(UUID)} through Spring's normal
 * inter-bean proxying instead, so the transaction boundary is real.
 *
 * <p>Every delete below except the refresh tokens goes through the owning domain's service, never
 * its repository: each domain decides for itself what deleting a user means to it — which is why
 * {@link OrganizationService#deletePersonalOrganizationOf} can clear a membership row while
 * refusing to delete a shared organization behind it. Refresh tokens are the one direct repository
 * call because they belong to this domain.
 */
@Service
@RequiredArgsConstructor
class UnverifiedAccountPurger {

  private final CredentialsService credentialsService;
  private final RefreshTokenRepository refreshTokens;
  private final OrganizationService organizationService;
  private final UserService userService;

  @Transactional
  void purgeOneAccount(UUID userId) {
    credentialsService.deleteAllForUser(userId);
    // An unverified user cannot log in, so normally has none of these — but refresh_tokens has a
    // real FK on both user_id and organization_id, so it must be cleared before the personal
    // organization and the user row below can be deleted.
    refreshTokens.deleteByUserId(userId);
    organizationService.deletePersonalOrganizationOf(userId);
    // The listing that produced this id was read in an earlier, already-committed transaction, so
    // it is only ever a hint: an account can complete /auth/verify in the gap before this purge
    // reaches it, and the deletes above would then have gutted a live account. The guard re-tests
    // that condition atomically, and throwing on false rolls this whole transaction back —
    // including those earlier deletes — rather than leaving a verified user with no credentials.
    //
    // The ordering above is also what keeps the guard deadlock-free. RegistrationService#verify
    // locks the verification-token row (inside consume) before the user row (inside
    // markEmailVerified), and this method takes the two in that same order, because
    // deleteAllForUser clears the verification tokens first. Two callers acquiring the same locks
    // in the same sequence cannot form an AB-BA cycle, so whichever arrives second simply waits
    // and then sees the other's committed result.
    if (!userService.deleteIfStillUnverified(userId)) {
      throw new AccountVerifiedDuringPurgeException(userId);
    }
  }
}
