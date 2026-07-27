package com.zarlania.api.common.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code zarlania.throttle} configuration block; see {@code application.yml}. */
@ConfigurationProperties(prefix = "zarlania.throttle")
public record ThrottleProperties(
    Duration window, int loginLimit, int registerLimit, int resendLimit, int refreshLimit) {}
