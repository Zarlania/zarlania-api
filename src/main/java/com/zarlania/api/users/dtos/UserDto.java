package com.zarlania.api.users.dtos;

import java.util.UUID;

public record UserDto(UUID id, String email, String username, boolean emailVerified) {}
