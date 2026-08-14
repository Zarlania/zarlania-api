package com.zarlania.api.auth.dtos;

/**
 * The CSRF token a client must echo back on {@code POST /auth/refresh} and {@code POST
 * /auth/logout}, together with the name of the header to put it in.
 *
 * @param headerName the header the token belongs in, so clients read the name from the server
 *     rather than hardcoding a value that only {@code SecurityConfig} really knows
 * @param token the token itself; the matching cookie travels on the same response, and the request
 *     is accepted only when both are present and agree
 */
public record CsrfTokenResponse(String headerName, String token) {}
