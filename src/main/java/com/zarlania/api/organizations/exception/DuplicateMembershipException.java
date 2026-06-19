package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when adding a member who already has a membership in the target organization. */
@Getter
public class DuplicateMembershipException extends RuntimeException {

  /** The organization the user already belongs to. */
  private final UUID organizationId;

  /** The user who already has a membership. */
  private final UUID userId;

  private DuplicateMembershipException(UUID organizationId, UUID userId) {
    super("The user already has a membership in the organization");
    this.organizationId = organizationId;
    this.userId = userId;
  }

  /**
   * Creates the exception for a duplicate membership.
   *
   * @param organizationId the organization id
   * @param userId the user already in the organization
   * @return an exception describing the conflict
   */
  public static DuplicateMembershipException forMembership(UUID organizationId, UUID userId) {
    return new DuplicateMembershipException(organizationId, userId);
  }
}
