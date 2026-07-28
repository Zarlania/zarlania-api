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
    userService.deleteById(userId);
  }
}
