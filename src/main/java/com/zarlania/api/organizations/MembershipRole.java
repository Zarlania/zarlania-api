package com.zarlania.api.organizations;

/** A user's role within an organization. */
public enum MembershipRole {
  /** Full control of the organization. Every organization always has at least one owner. */
  OWNER,
  /** A non-owner participant in a {@code GENERAL} organization. */
  MEMBER
}
