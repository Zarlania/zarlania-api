package com.zarlania.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code zarlania.auth} configuration block; see {@code application.yml}. */
@ConfigurationProperties(prefix = "zarlania.auth")
public record AuthProperties(
    String issuer,
    Duration accessTokenTtl,
    Duration refreshFamilyLifetime,
    Duration verificationTokenTtl,
    Duration unverifiedAccountMaxAge,
    boolean cookieSecure,
    String jwtPrivateKeyPem,
    String jwtRetiredPublicKeysPem,
    String appBaseUrl) {}
