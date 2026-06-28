package com.zarlania.api.identity.service;

import com.zarlania.api.identity.dto.Account;
import com.zarlania.api.logging.LogSanitizer;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.service.OrganizationService;
import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates account creation across the {@code users} and {@code organizations} domains. The
 * public surface of the {@code identity} domain. Injects each domain's service as a Spring bean and
 * exchanges only DTOs (ADR-0011).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdentityService {

  private final UserService userService;
  private final OrganizationService organizationService;

  /**
   * Creates an account — a user and their personal organization, named after the username — in a
   * single transaction. Because both delegated services join this transaction, a failure creating
   * the organization rolls back the user creation too, so no orphaned user remains.
   *
   * @param email the new user's email
   * @param username the new user's unique public handle
   * @return the created account (user + personal organization)
   */
  @Transactional
  public Account createAccount(String email, String username) {
    User user = userService.create(email, username);
    Organization personalOrganization =
        organizationService.createPersonalOrganization(user.id(), user.username());
    // Log identifiers only — never the email (PII). The user id is the stable, non-sensitive
    // surrogate for tracing this account through the system. Sanitised via LogSanitizer to keep
    // the CRLF_INJECTION_LOGS detector satisfied (ids are UUIDs, but the records they hang off
    // carry user-supplied fields, so SpotBugs treats any access as tainted).
    log.info(
        "Created account: userId={}, organizationId={}",
        LogSanitizer.forLog(user.id()),
        LogSanitizer.forLog(personalOrganization.id()));
    return new Account(user, personalOrganization);
  }
}
