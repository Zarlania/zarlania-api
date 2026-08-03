package com.zarlania.api.users.dtos;

import java.util.UUID;

/**
 * How a user crosses out of its domain. Deliberately carries no password material and no tokens —
 * those live in the credentials domain and have no business travelling with an identity.
 *
 * @param emailVerified whether the address has been proved; false is what blocks login
 */
public record UserDto(UUID id, String email, String username, boolean emailVerified) {}
