package com.zarlania.api.organizations;

/**
 * The kind of organization: a user's private {@code PERSONAL} space or a shared {@code GENERAL}
 * (company) account.
 */
public enum OrganizationType {
  /** A user's single personal organization: exactly one owner and no other members. */
  PERSONAL,
  /** A shared organization that may have multiple owners and members. */
  GENERAL
}
