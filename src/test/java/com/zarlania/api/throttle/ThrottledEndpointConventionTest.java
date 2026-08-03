package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.throttle.ThrottleProperties.EndpointLimits;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guards the four ways {@link Throttled} can be wrong without anything noticing until a request
 * hits it in production.
 *
 * <p>An endpoint name with no configured limits would leave the route unthrottled. An {@code
 * accountFrom} naming a component no argument declares would leave the per-account bucket off. An
 * {@code accountFrom} without a matching {@code account-limit}, or the reverse, means one half of a
 * bucket was added and the other forgotten — they are declared in different files, so nothing else
 * connects them. And a configured endpoint no handler claims is a limit somebody will tune in the
 * belief that it is in force.
 *
 * <p>Reads the real {@code application.yml} and scans the real controllers, so it fails the build
 * on that drift rather than merely describing the rule.
 */
class ThrottledEndpointConventionTest {

  private static final String BASE_PACKAGE = "com.zarlania.api";

  private static final ThrottleProperties PROPERTIES = loadThrottleProperties();

  @ParameterizedTest(name = "{0}")
  @MethodSource("throttledHandlers")
  void everyThrottledEndpointHasConfiguredLimits(String name, Method handler) {
    String endpoint = handler.getAnnotation(Throttled.class).endpoint();

    assertThat(PROPERTIES.endpoints())
        .as("zarlania.throttle.endpoints entry for %s, declared on %s", endpoint, name)
        .containsKey(endpoint);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("throttledHandlers")
  void everyAccountFromNamesAComponentTheHandlerActuallyReceives(String name, Method handler) {
    String accountFrom = handler.getAnnotation(Throttled.class).accountFrom();
    if (accountFrom.isEmpty()) {
      return;
    }

    assertThat(recordComponentNames(handler))
        .as("record component named by accountFrom on %s", name)
        .contains(accountFrom);
  }

  // The two halves of an account bucket are declared in different files, so nothing but this stops
  // one being added without the other. Either omission is silent: no annotation means the limit is
  // never applied, no limit means the annotation cannot be honoured.
  @ParameterizedTest(name = "{0}")
  @MethodSource("throttledHandlers")
  void namingAnAccountAndConfiguringAnAccountLimitGoTogether(String name, Method handler) {
    Throttled throttled = handler.getAnnotation(Throttled.class);
    EndpointLimits limits = PROPERTIES.endpoints().get(throttled.endpoint());
    boolean namesAnAccount = !throttled.accountFrom().isEmpty();

    assertThat(limits.accountLimitIfPresent().isPresent())
        .as("account-limit configured for %s, which names an account: %s", name, namesAnAccount)
        .isEqualTo(namesAnAccount);
  }

  // Configuration nothing reads is configuration that will drift: a limit tuned here and never
  // applied reads, to whoever tunes it next, as a limit that is in force.
  @Test
  void everyConfiguredEndpointIsClaimedBySomeHandler() {
    Set<String> annotated =
        throttledHandlers()
            .map(arguments -> (Method) arguments.get()[1])
            .map(handler -> handler.getAnnotation(Throttled.class).endpoint())
            .collect(Collectors.toSet());

    assertThat(PROPERTIES.endpoints().keySet()).isEqualTo(annotated);
  }

  private static Stream<Arguments> throttledHandlers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

    return scanner.findCandidateComponents(BASE_PACKAGE).stream()
        .map(ThrottledEndpointConventionTest::load)
        .flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
        .filter(method -> method.isAnnotationPresent(Throttled.class))
        .map(
            method ->
                Arguments.of(
                    method.getDeclaringClass().getSimpleName() + "#" + method.getName(), method));
  }

  private static List<String> recordComponentNames(Method handler) {
    return Arrays.stream(handler.getParameterTypes())
        .filter(Class::isRecord)
        .flatMap(type -> Arrays.stream(type.getRecordComponents()))
        .map(RecordComponent::getName)
        .toList();
  }

  private static Class<?> load(BeanDefinition definition) {
    try {
      return Class.forName(definition.getBeanClassName());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ThrottleProperties loadThrottleProperties() {
    try {
      Binder binder =
          new Binder(
              ConfigurationPropertySources.from(
                  new YamlPropertySourceLoader()
                      .load("application.yml", new ClassPathResource("application.yml"))));
      return binder
          .bind("zarlania.throttle", ThrottleProperties.class)
          .orElseThrow(() -> new IllegalStateException("No zarlania.throttle block found"));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read application.yml", e);
    }
  }
}
