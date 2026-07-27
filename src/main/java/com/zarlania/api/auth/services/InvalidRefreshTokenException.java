package com.zarlania.api.auth.services;

/** Thrown when a refresh token is unknown, already revoked, or its family has expired. */
public class InvalidRefreshTokenException extends RuntimeException {}
