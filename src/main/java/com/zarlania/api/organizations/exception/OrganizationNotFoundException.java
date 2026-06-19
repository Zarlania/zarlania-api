package com.zarlania.api.organizations.exception;

import java.util.UUID;
import lombok.Getter;

/** Thrown when an operation targets an organization id that does not exist. */
@Getter
public class OrganizationNotFoundException extends RuntimeException {

  /** The id that did not resolve to an organization. */
  private final UUID organizationId;

  private OrganizationNotFoundException(UUID organizationId) {
    super("No organization exists with the given id");
    this.organizationId = organizationId;
  }

  /**
   * Creates the exception for a missing organization.
   *
   * @param organizationId the id that did not resolve
   * @return an exception describing the miss
   */
  public static OrganizationNotFoundException forId(UUID organizationId) {
    return new OrganizationNotFoundException(organizationId);
  }
}
