package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zarlania.api.errors.ApiException;
import com.zarlania.api.http.CloudflareClientIpResolver;
import java.time.Duration;
import java.util.Map;
import org.aspectj.lang.JoinPoint;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit-level: the aspect is exercised directly with a hand-built join point rather than through a
 * proxy, so the limiter can be stubbed and every branch reached without a context. That the
 * annotation is actually woven onto the real handlers is covered end to end by {@code
 * ThrottleEndToEndTest}.
 */
class ThrottleAspectTest {

  private static final String CLIENT_IP = "203.0.113.7";
  private static final int IP_LIMIT = 10;
  private static final int ACCOUNT_LIMIT = 5;

  /** Source of real {@link Throttled} instances — an annotation cannot be constructed by hand. */
  private static final class AnnotatedHandlers {

    @Throttled(endpoint = "login", accountFrom = "identifier")
    void withAccountBucket() {}

    @Throttled(endpoint = "refresh")
    void withoutAccountBucket() {}
  }

  private final RateLimiter rateLimiter = mock(RateLimiter.class);
  private final JoinPoint joinPoint = mock(JoinPoint.class);

  private ThrottleAspect aspect;

  @BeforeEach
  void bindRequestAndConfigureLimits() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(CLIENT_IP);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    aspect =
        new ThrottleAspect(
            rateLimiter,
            new ThrottleProperties(
                Duration.ofMinutes(1),
                Map.of(
                    "login", new EndpointLimits(IP_LIMIT, ACCOUNT_LIMIT),
                    "refresh", new EndpointLimits(IP_LIMIT, null)),
                80,
                Duration.ofDays(1)),
            new CloudflareClientIpResolver());
  }

  @AfterEach
  void unbindRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void aRequestWithRoomInBothBucketsReachesTheHandler() {
    permitEverything();
    when(joinPoint.getArgs())
        .thenReturn(new Object[] {new LoginBody("bob@example.com", "hunter2")});

    assertThatCode(() -> aspect.enforce(joinPoint, throttled("withAccountBucket")))
        .doesNotThrowAnyException();
  }

  @Test
  void bothBucketsAreConsumedWithTheirOwnLimits() {
    permitEverything();
    when(joinPoint.getArgs())
        .thenReturn(new Object[] {new LoginBody("bob@example.com", "hunter2")});

    aspect.enforce(joinPoint, throttled("withAccountBucket"));

    verify(rateLimiter).tryConsume("login:" + CLIENT_IP, IP_LIMIT);
    verify(rateLimiter).tryConsume("login:acct:bob@example.com", ACCOUNT_LIMIT);
  }

  @Test
  void anEndpointNamingNoAccountConsumesTheClientBucketOnly() {
    permitEverything();
    when(joinPoint.getArgs()).thenReturn(new Object[] {"a-cookie-value"});

    aspect.enforce(joinPoint, throttled("withoutAccountBucket"));

    verify(rateLimiter).tryConsume("refresh:" + CLIENT_IP, IP_LIMIT);
    verify(rateLimiter, never()).tryConsume(eq("refresh:acct:a-cookie-value"), anyInt());
  }

  @Test
  void anExhaustedClientBucketIsThrottledBeforeTheAccountBucketIsEvenTouched() {
    when(rateLimiter.tryConsume("login:" + CLIENT_IP, IP_LIMIT))
        .thenReturn(ThrottleDecision.refused(Duration.ofSeconds(30)));
    when(joinPoint.getArgs())
        .thenReturn(new Object[] {new LoginBody("bob@example.com", "hunter2")});

    assertThatThrownBy(() -> aspect.enforce(joinPoint, throttled("withAccountBucket")))
        .isInstanceOf(ApiException.class)
        .extracting(exception -> ((ApiException) exception).getErrorCode())
        .isEqualTo(ThrottleErrorCode.THROTTLED);

    verify(rateLimiter, never()).tryConsume("login:acct:bob@example.com", ACCOUNT_LIMIT);
  }

  @Test
  void anExhaustedAccountBucketIsThrottledEvenWhenTheClientBucketHasRoom() {
    when(rateLimiter.tryConsume("login:" + CLIENT_IP, IP_LIMIT))
        .thenReturn(ThrottleDecision.permitted());
    when(rateLimiter.tryConsume("login:acct:bob@example.com", ACCOUNT_LIMIT))
        .thenReturn(ThrottleDecision.refused(Duration.ofSeconds(30)));
    when(joinPoint.getArgs())
        .thenReturn(new Object[] {new LoginBody("bob@example.com", "hunter2")});

    assertThatThrownBy(() -> aspect.enforce(joinPoint, throttled("withAccountBucket")))
        .isInstanceOf(ApiException.class);
  }

  // RFC 9110 §10.2.3 measures Retry-After in whole seconds, so a sub-second remainder has to round
  // up: a client that waits exactly what it is told must not arrive to a second rejection.
  @ParameterizedTest(name = "{0}ms left -> Retry-After: {1}")
  @CsvSource({"30000, 30", "1, 1", "0, 1", "59001, 60", "59999, 60", "-500, 1"})
  void aThrottledResponseAdvertisesWholeSecondsRoundedUpAndNeverBelowOne(
      long remainingMillis, String expectedHeader) {
    when(rateLimiter.tryConsume("refresh:" + CLIENT_IP, IP_LIMIT))
        .thenReturn(ThrottleDecision.refused(Duration.ofMillis(remainingMillis)));
    when(joinPoint.getArgs()).thenReturn(new Object[0]);

    assertThatThrownBy(() -> aspect.enforce(joinPoint, throttled("withoutAccountBucket")))
        .asInstanceOf(InstanceOfAssertFactories.type(ApiException.class))
        .extracting(ApiException::getResponseHeaders)
        .isEqualTo(Map.of(HttpHeaders.RETRY_AFTER, expectedHeader));
  }

  // An endpoint annotated as throttled but missing from configuration would otherwise run with no
  // limit at all, which is the one failure mode a throttle must never have.
  @Test
  void anEndpointWithNoConfiguredLimitsFailsRatherThanRunningUnlimited() {
    ThrottleAspect unconfigured =
        new ThrottleAspect(
            rateLimiter,
            new ThrottleProperties(Duration.ofMinutes(1), Map.of(), 80, Duration.ofDays(1)),
            new CloudflareClientIpResolver());

    assertThatThrownBy(() -> unconfigured.enforce(joinPoint, throttled("withoutAccountBucket")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refresh");
  }

  @Test
  void anEndpointNamingAnAccountButConfiguringNoAccountLimitFails() {
    ThrottleAspect missingAccountLimit =
        new ThrottleAspect(
            rateLimiter,
            new ThrottleProperties(
                Duration.ofMinutes(1),
                Map.of("login", new EndpointLimits(IP_LIMIT, null)),
                80,
                Duration.ofDays(1)),
            new CloudflareClientIpResolver());
    when(rateLimiter.tryConsume("login:" + CLIENT_IP, IP_LIMIT))
        .thenReturn(ThrottleDecision.permitted());

    assertThatThrownBy(() -> missingAccountLimit.enforce(joinPoint, throttled("withAccountBucket")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("account-limit");
  }

  private void permitEverything() {
    when(rateLimiter.tryConsume(anyString(), anyInt())).thenReturn(ThrottleDecision.permitted());
  }

  private static Throttled throttled(String handlerName) {
    try {
      return AnnotatedHandlers.class.getDeclaredMethod(handlerName).getAnnotation(Throttled.class);
    } catch (NoSuchMethodException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
