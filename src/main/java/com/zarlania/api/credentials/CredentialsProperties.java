package com.zarlania.api.credentials;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.credentials} configuration block; see {@code application.yml}.
 *
 * <p>Owned by this domain rather than read from {@code AuthProperties}: {@code auth} composes
 * {@code credentials} through its services, so a {@code credentials} class binding an {@code auth}
 * properties record would invert that dependency and stop this domain being liftable on its own.
 */
@ConfigurationProperties(prefix = "zarlania.credentials")
public record CredentialsProperties(Duration verificationTokenTtl, int maxConcurrentHashes) {}
