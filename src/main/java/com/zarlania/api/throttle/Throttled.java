package com.zarlania.api.throttle;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler as rate limited. {@link ThrottleAspect} enforces it; the handler
 * itself holds no throttling code and never learns that it is throttled.
 *
 * <p>This is an aspect rather than a servlet filter or a {@code HandlerInterceptor} because of
 * {@link #accountFrom()}: the per-account bucket keys on a field of the parsed request body, and
 * both of those run before argument resolution, so neither can see it without buffering and
 * re-parsing the body. Advice around the handler method runs after binding, which lets one
 * mechanism carry both bucket kinds.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Throttled {

  /**
   * Names the bucket family. Also the key into {@code zarlania.throttle.endpoints}, which is where
   * the limits themselves live — they differ between environments, so they are configuration rather
   * than an annotation value.
   */
  String endpoint();

  /**
   * The record component of this handler's request body that names the account being acted on, for
   * the second bucket keyed on the account rather than the caller's address. Empty means the
   * endpoint names no account and gets a per-IP bucket only.
   *
   * <p>A name rather than a type, because the component differs per request record ({@code email}
   * on register and resend, {@code identifier} on login). {@code ThrottledEndpointConventionTest}
   * checks every value here resolves against the annotated handler's parameters, so a typo fails
   * the build rather than a request.
   */
  String accountFrom() default "";
}
