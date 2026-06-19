package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when creating a second personal organization for an owner who already has one. */
@Getter
public class PersonalOrganizationAlreadyExistsException extends RuntimeException {

  /** The owner who already has a personal organization. */
  private final UUID ownerUserId;

  private PersonalOrganizationAlreadyExistsException(UUID ownerUserId) {
    super("The user already owns a personal organization");
    this.ownerUserId = ownerUserId;
  }

  /**
   * Creates the exception for an owner who already has a personal organization.
   *
   * @param ownerUserId the owner's user id
   * @return an exception describing the conflict
   */
  public static PersonalOrganizationAlreadyExistsException forOwner(UUID ownerUserId) {
    return new PersonalOrganizationAlreadyExistsException(ownerUserId);
  }
}
