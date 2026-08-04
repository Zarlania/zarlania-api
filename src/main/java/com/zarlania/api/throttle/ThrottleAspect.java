package com.zarlania.api.throttle;

import com.zarlania.api.errors.ApiException;
import com.zarlania.api.errors.ErrorCode;
import com.zarlania.api.http.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Enforces {@link Throttled} on the handlers that carry it, so no controller holds throttling code
 * of its own.
 *
 * <p>Each annotated handler gets a bucket keyed on the caller's address, and — where the request
 * names an account — a second, independent bucket keyed on that account across every address. Both
 * are counted over {@code zarlania.throttle.window}. Exceeding either is a 429 carrying a {@code
 * Retry-After} header, so a client backs off for as long as the window actually needs rather than
 * guessing.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class ThrottleAspect {

  private static final String THROTTLED_MESSAGE = "Too many requests";

  // RFC 9110 §10.2.3 measures Retry-After in whole seconds. Rounding up rather than down, and never
  // below one, so a client that obeys the header to the letter always waits until the window has
  // genuinely refilled instead of retrying into a second rejection.
  private static final long MILLIS_PER_SECOND = 1000L;
  private static final long MINIMUM_RETRY_AFTER_SECONDS = 1L;

  private final RateLimiter rateLimiter;
  private final ThrottleProperties throttleProperties;

  /**
   * Applies both buckets before the handler runs.
   *
   * <p>Advises the handler method rather than filtering the request because the per-account bucket
   * keys on a field of the parsed body; see {@link Throttled}.
   *
   * @throws ApiException with {@link ErrorCode#THROTTLED} if either bucket is exhausted
   */
  @Before("@annotation(throttled)")
  public void enforce(JoinPoint joinPoint, Throttled throttled) {
    EndpointLimits limits = throttleProperties.limitsFor(throttled.endpoint());
    String clientIp = ClientIpResolver.resolve(currentRequest());
    consumeOrThrow(ThrottleKeys.forClient(throttled.endpoint(), clientIp), limits.limit());

    if (throttled.accountFrom().isEmpty()) {
      return;
    }
    int accountLimit =
        limits
            .accountLimitIfPresent()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Endpoint declares accountFrom but configures no account-limit: "
                            + throttled.endpoint()));
    String identifier = AccountIdentifierReader.read(joinPoint.getArgs(), throttled.accountFrom());
    consumeOrThrow(ThrottleKeys.forAccount(throttled.endpoint(), identifier), accountLimit);
  }

  private void consumeOrThrow(String key, int limit) {
    ThrottleDecision decision = rateLimiter.tryConsume(key, limit);
    if (!decision.allowed()) {
      throw new ApiException(ErrorCode.THROTTLED, THROTTLED_MESSAGE, retryAfterHeader(decision));
    }
  }

  private static Map<String, String> retryAfterHeader(ThrottleDecision decision) {
    return Map.of(
        HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(decision.retryAfter())));
  }

  private static long retryAfterSeconds(Duration retryAfter) {
    long roundedUp = (retryAfter.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND;
    return Math.max(MINIMUM_RETRY_AFTER_SECONDS, roundedUp);
  }

  /**
   * The servlet request is taken from the request-bound context rather than a handler parameter, so
   * that a throttled endpoint does not have to declare an HttpServletRequest argument it otherwise
   * has no use for. Every handler this advice matches is invoked on a request thread, so the
   * attributes are always bound.
   */
  private static HttpServletRequest currentRequest() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
        .getRequest();
  }
}
