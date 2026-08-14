package com.zarlania.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The validation this record does as it is bound, which is the only logic it holds.
 *
 * <p>Each of these is read without re-checking by something that cannot report a bad value itself.
 * The issuer is written into every access token's {@code iss} and compared on the way back in; a
 * non-positive lifetime mints a credential that has already expired, which reads to a client as a
 * server rejecting tokens it just issued; and the base URL is the origin of every verification link
 * this service emails, so a blank one sends people to a relative path.
 */
class AuthPropertiesTest {

  private static final String ISSUER = "https://api.zarlania.test";
  private static final String APP_BASE_URL = "https://zarlania.test";
  private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
  private static final Duration FAMILY_LIFETIME = Duration.ofDays(30);
  private static final Duration MAX_AGE = Duration.ofDays(7);

  @Test
  void bindsWhenEveryPropertyIsUsable() {
    AuthProperties properties = properties();

    assertThat(properties.issuer()).isEqualTo(ISSUER);
    assertThat(properties.accessTokenTtl()).isEqualTo(ACCESS_TOKEN_TTL);
    assertThat(properties.refreshFamilyLifetime()).isEqualTo(FAMILY_LIFETIME);
    assertThat(properties.unverifiedAccountMaxAge()).isEqualTo(MAX_AGE);
    assertThat(properties.appBaseUrl()).isEqualTo(APP_BASE_URL);
    assertThat(properties.cookieSecure()).isTrue();
  }

  // Both key properties are left alone on purpose. A blank private key is how every non-production
  // deployment runs, and JwtKeys is the only thing that knows the profile, so whether a missing one
  // is fatal — and whether a retired-key block parses — is its decision. Checking either here would
  // take that away and break local development, which starts with neither set.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "-----BEGIN PUBLIC KEY-----"})
  void leavesBothKeyPropertiesToJwtKeys(String keyMaterial) {
    assertThatCode(
            () ->
                new AuthProperties(
                    ISSUER,
                    ACCESS_TOKEN_TTL,
                    FAMILY_LIFETIME,
                    MAX_AGE,
                    true,
                    keyMaterial,
                    keyMaterial,
                    APP_BASE_URL))
        .doesNotThrowAnyException();
  }

  // Written into every access token's iss claim and compared on the way back in, so a blank one
  // would be minted and then rejected by this same service.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsABlankIssuerNamingTheProperty(String issuer) {
    assertThatThrownBy(
            () ->
                new AuthProperties(
                    issuer, ACCESS_TOKEN_TTL, FAMILY_LIFETIME, MAX_AGE, true, "", "", APP_BASE_URL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.auth.issuer");
  }

  // The origin of every verification link this service emails. Blank sends the recipient to a
  // relative path, so the account can never be verified and never be logged into.
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void rejectsABlankAppBaseUrlNamingTheProperty(String appBaseUrl) {
    assertThatThrownBy(
            () ->
                new AuthProperties(
                    ISSUER, ACCESS_TOKEN_TTL, FAMILY_LIFETIME, MAX_AGE, true, "", "", appBaseUrl))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zarlania.auth.app-base-url");
  }

  @ParameterizedTest(name = "{0} must be configured")
  @MethodSource("durationProperties")
  void rejectsAMissingDurationNamingTheProperty(
      String property, Function<Duration, AuthProperties> withDuration) {
    assertThatThrownBy(() -> withDuration.apply(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(property);
  }

  // Zero is what an unset or misspelled duration key binds to, and none of these three degrade
  // gracefully at zero: an access token expires at the instant it is minted, a refresh family is
  // dead before its first rotation, and a max age purges every account the moment it registers.
  @ParameterizedTest(name = "{0} must be positive, not {2}")
  @MethodSource("nonPositiveDurationCases")
  void rejectsANonPositiveDurationNamingTheProperty(
      String property, Function<Duration, AuthProperties> withDuration, Duration nonPositive) {
    assertThatThrownBy(() -> withDuration.apply(nonPositive))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(property)
        .hasMessageContaining(nonPositive.toString());
  }

  static Stream<Arguments> durationProperties() {
    return Stream.of(
        Arguments.of(
            "zarlania.auth.access-token-ttl",
            (Function<Duration, AuthProperties>)
                value -> withDurations(value, FAMILY_LIFETIME, MAX_AGE)),
        Arguments.of(
            "zarlania.auth.refresh-family-lifetime",
            (Function<Duration, AuthProperties>)
                value -> withDurations(ACCESS_TOKEN_TTL, value, MAX_AGE)),
        Arguments.of(
            "zarlania.auth.unverified-account-max-age",
            (Function<Duration, AuthProperties>)
                value -> withDurations(ACCESS_TOKEN_TTL, FAMILY_LIFETIME, value)));
  }

  static Stream<Arguments> nonPositiveDurationCases() {
    return durationProperties()
        .flatMap(
            property ->
                Stream.of(Duration.ZERO, Duration.ofMinutes(-1))
                    .map(value -> Arguments.of(property.get()[0], property.get()[1], value)));
  }

  private static AuthProperties properties() {
    return withDurations(ACCESS_TOKEN_TTL, FAMILY_LIFETIME, MAX_AGE);
  }

  private static AuthProperties withDurations(
      Duration accessTokenTtl, Duration refreshFamilyLifetime, Duration unverifiedAccountMaxAge) {
    return new AuthProperties(
        ISSUER,
        accessTokenTtl,
        refreshFamilyLifetime,
        unverifiedAccountMaxAge,
        true,
        "",
        "",
        APP_BASE_URL);
  }
}
