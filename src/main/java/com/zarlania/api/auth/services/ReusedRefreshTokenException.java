package com.zarlania.api.auth.services;

/**
 * Thrown when a refresh token that was already redeemed is presented again — treated as evidence
 * the token was stolen, so the whole family is revoked before this is thrown.
 */
public class ReusedRefreshTokenException extends RuntimeException {}
