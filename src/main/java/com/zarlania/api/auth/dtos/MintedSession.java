package com.zarlania.api.auth.dtos;

/**
 * What a successful login or refresh hands back: the two halves of a session, issued together.
 *
 * @param accessToken the short-lived bearer token, returned in the response body
 * @param refresh the long-lived refresh token, which the controller writes to an HttpOnly cookie
 */
public record MintedSession(String accessToken, IssuedRefreshToken refresh) {}
