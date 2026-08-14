package com.zarlania.api.throttle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Pulls the account identifier named by {@link Throttled#accountFrom()} out of a handler's
 * arguments, so {@link ThrottleAspect} can key the per-account bucket on it.
 *
 * <p>Reflection is what the annotation's shape costs: the component holding the identifier differs
 * per request record ({@code email} on register and resend, {@code identifier} on login), so it
 * cannot be reached through a shared type. It is confined to this class, and {@code
 * ThrottledEndpointConventionTest} resolves every {@code accountFrom} in the codebase against its
 * handler, so a name that does not exist fails the build rather than a request in production.
 *
 * <p>Deliberately uncached. One lookup walks the record components of one or two arguments, which
 * is nothing next to the Argon2 hash and database round trips waiting on the other side of the
 * handler, and a cache here would be state to reason about for no measurable gain.
 */
public final class AccountIdentifierReader {

  private AccountIdentifierReader() {}

  /**
   * Reads the value of record component {@code componentName} from the first argument that declares
   * it.
   *
   * @param arguments the handler's arguments, as resolved by Spring MVC
   * @param componentName the record component holding the account identifier
   * @throws IllegalStateException if no argument is a record with that component, or the accessor
   *     cannot be invoked — both mean the annotation and the handler have drifted apart
   */
  public static String read(Object[] arguments, String componentName) {
    return Arrays.stream(arguments)
        .map(argument -> readFrom(argument, componentName))
        .flatMap(Optional::stream)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No handler argument is a record with a component named: " + componentName));
  }

  private static Optional<String> readFrom(Object argument, String componentName) {
    if (argument == null || !argument.getClass().isRecord()) {
      return Optional.empty();
    }
    return Arrays.stream(argument.getClass().getRecordComponents())
        .filter(component -> component.getName().equals(componentName))
        .findFirst()
        .map(RecordComponent::getAccessor)
        .map(accessor -> invoke(accessor, argument));
  }

  private static String invoke(Method accessor, Object argument) {
    try {
      return Objects.toString(accessor.invoke(argument), "");
    } catch (IllegalAccessException | InvocationTargetException exception) {
      throw new IllegalStateException(
          "Cannot read " + accessor.getName() + " for throttling", exception);
    }
  }
}
