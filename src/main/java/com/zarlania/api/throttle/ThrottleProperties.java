package com.zarlania.api.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code zarlania.throttle} configuration block; see {@code application.yml}.
 *
 * <p>The {@code *AccountLimit} values are a second, independent set of buckets keyed on the account
 * a request names rather than on where it came from — per-IP limits alone leave an attacker with
 * many addresses unbounded against one known account. See {@code AuthController}.
 */
@ConfigurationProperties(prefix = "zarlania.throttle")
public record ThrottleProperties(
    Duration window,
    int loginLimit,
    int registerLimit,
    int resendLimit,
    int refreshLimit,
    int csrfLimit,
    int loginAccountLimit,
    int registerAccountLimit,
    int resendAccountLimit,
    int emailBudgetLimit,
    Duration emailBudgetWindow) {}
