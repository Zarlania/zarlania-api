package com.zarlania.api.organizations.exception;

import lombok.Getter;

/** Thrown when creating an organization whose name already belongs to an existing organization. */
@Getter
public class OrganizationNameAlreadyExistsException extends RuntimeException {

  /** The conflicting organization name. */
  private final String name;

  private OrganizationNameAlreadyExistsException(String name) {
    super("An organization already exists with the given name");
    this.name = name;
  }

  /**
   * Creates the exception for a conflicting organization name.
   *
   * @param name the name already in use
   * @return an exception describing the conflict
   */
  public static OrganizationNameAlreadyExistsException forName(String name) {
    return new OrganizationNameAlreadyExistsException(name);
  }
}
