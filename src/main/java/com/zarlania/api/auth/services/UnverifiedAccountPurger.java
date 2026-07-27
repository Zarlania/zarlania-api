package com.zarlania.api.auth.services;

import com.zarlania.api.auth.repositories.RefreshTokenRepository;
import com.zarlania.api.credentials.repositories.EmailVerificationTokenRepository;
import com.zarlania.api.credentials.repositories.PasswordCredentialRepository;
import com.zarlania.api.organizations.services.OrganizationService;
import com.zarlania.api.users.repositories.UserRepository;
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
 */
@Service
@RequiredArgsConstructor
class UnverifiedAccountPurger {

  private final EmailVerificationTokenRepository verificationTokens;
  private final PasswordCredentialRepository passwordCredentials;
  private final RefreshTokenRepository refreshTokens;
  private final OrganizationService organizationService;
  private final UserRepository users;

  @Transactional
  void purgeOneAccount(UUID userId) {
    verificationTokens.deleteByUserId(userId);
    passwordCredentials.deleteByUserId(userId);
    // An unverified user cannot log in, so normally has none of these — but refresh_tokens has a
    // real FK on both user_id and organization_id, so it must be cleared before the personal
    // organization and the user row below can be deleted.
    refreshTokens.deleteByUserId(userId);
    organizationService.deletePersonalOrganizationOf(userId);
    users.deleteById(userId);
  }
}
