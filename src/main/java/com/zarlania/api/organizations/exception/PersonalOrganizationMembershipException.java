package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/**
 * Thrown when adding a member or owner to a {@code PERSONAL} organization, which admits no members
 * beyond its single owner.
 */
@Getter
public class PersonalOrganizationMembershipException extends RuntimeException {

  /** The personal organization that may not take additional members. */
  private final UUID organizationId;

  private PersonalOrganizationMembershipException(UUID organizationId) {
    super("A personal organization admits no members beyond its owner");
    this.organizationId = organizationId;
  }

  /**
   * Creates the exception for the offending personal organization.
   *
   * @param organizationId the personal organization's id
   * @return an exception describing the rejection
   */
  public static PersonalOrganizationMembershipException forOrganization(UUID organizationId) {
    return new PersonalOrganizationMembershipException(organizationId);
  }
}
