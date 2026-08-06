package com.zarlania.api.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.email} configuration block; see {@code application.yml}.
 *
 * @param resendApiKey the provider credential, blank when none is configured — which is a startup
 *     failure in production and a fall back to logging everywhere else
 * @param resendBaseUrl the provider's API root, configurable so a different Resend-compatible
 *     endpoint, or a stub, can be pointed at without a code change
 * @param connectTimeout how long a send may wait for the provider to accept a connection
 * @param readTimeout how long a send may wait for the provider's response once connected. Both
 *     timeouts are bounded because the JDK's HTTP client has no default for either and {@link
 *     #dispatchThreads} is small: one send that waits forever stalls every message behind it.
 * @param dispatchThreads how many sends may be in flight at once
 * @param dispatchQueueCapacity how many sends may wait for a dispatch thread before new ones are
 *     rejected and logged. Bounded so a provider outage cannot grow the queue until the instance is
 *     killed for memory.
 */
@ConfigurationProperties(prefix = "zarlania.email")
public record EmailProperties(
    String from,
    String resendApiKey,
    String resendBaseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int dispatchThreads,
    int dispatchQueueCapacity) {}
