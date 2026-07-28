package com.zarlania.api.auth;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.zarlania.api.auth.services.JwtKeys;
import com.zarlania.api.auth.services.TokenClaims;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Wires the resource-server filter chain: every request needs a valid JWT except the public paths
 * below, and a valid JWT is turned into an {@link AuthPrincipal} rather than the default
 * scope-based {@code Jwt} authentication.
 */
// SPRING_CSRF_PROTECTION_DISABLED: FindSecBugs reports this against the class, not the
// csrf().disable() call site, because the detector cannot see the authentication model. CSRF
// tokens defend credentials the browser attaches *ambiently* (cookies); this chain is
// STATELESS and reads credentials from the Authorization header, which is never ambient. The
// one cookie-borne credential on this service is the zarlania_refresh cookie that
// POST /auth/refresh reads (Task 12); it is defended instead by SameSite=Strict (keeps it off
// cross-site requests), Path=/auth (keeps it off every other endpoint), and the CORS origin
// list being an explicit allow-list rather than a wildcard (also what makes
// allowCredentials(true) below safe). Re-examine this suppression if any of those three change.
@SuppressFBWarnings(
    value = "SPRING_CSRF_PROTECTION_DISABLED",
    justification =
        "Stateless bearer-token chain, not cookie/session auth; the one cookie this service"
            + " has is scoped by SameSite=Strict, Path=/auth, and a non-wildcard CORS"
            + " allow-list (see class comment).")
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  // Auth issuance/verification and the JWKS publication endpoint itself cannot require a
  // token — nothing has one yet. The health probe is what Render polls to keep the service up.
  private static final String[] PUBLIC_PATHS = {
    "/auth/**", "/.well-known/jwks.json", "/actuator/health"
  };

  private static final String CORS_ALL_PATHS_PATTERN = "/**";
  private static final List<String> CORS_ALLOWED_METHODS =
      List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS");
  private static final List<String> CORS_ALLOWED_HEADERS = List.of("Authorization", "Content-Type");

  private final JwtKeys jwtKeys;
  private final AuthProperties authProperties;

  @Bean
  // THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION: HttpSecurity#build's `throws Exception` is
  // dictated by Spring Security's own SecurityBuilder<O> contract (see HttpSecurity,
  // DefaultSecurityFilterChain) — this method cannot narrow it. Catching Exception here to
  // swallow the checked type would itself be banned by Checkstyle's IllegalCatch rule, so no
  // code change can satisfy the detector; the signature is fixed, not stylistic.
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      justification =
          "HttpSecurity#build()'s throws clause is fixed by the SecurityBuilder<O> contract"
              + " and cannot be narrowed; catching Exception to remove it would violate"
              + " Checkstyle's IllegalCatch rule.")
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(authConverter())));
    return http.build();
  }

  // createDefaultWithIssuer, not the default validator: the default checks only that the token has
  // not expired. Pinning `iss` means a token minted by some other issuer that happens to verify
  // against a key in this set — the case a future shared or mistakenly reused key would create —
  // is rejected rather than accepted as one of ours.
  @Bean
  public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(jwtKeys.publicJwkSet())).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(authProperties.issuer()));
    return decoder;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${zarlania.cors.allowed-origins}") String allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(splitAndTrim(allowedOrigins));
    configuration.setAllowedMethods(CORS_ALLOWED_METHODS);
    configuration.setAllowedHeaders(CORS_ALLOWED_HEADERS);
    // Task 12's refresh cookie only reaches the browser if the response can carry
    // Set-Cookie back through CORS, which requires allowCredentials. This is safe only
    // because the origin list above is an explicit allow-list, never a wildcard — Spring
    // rejects allowCredentials(true) paired with "*" at startup.
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration(CORS_ALL_PATHS_PATTERN, configuration);
    return source;
  }

  private static List<String> splitAndTrim(String commaSeparatedValues) {
    return List.of(commaSeparatedValues.split(",")).stream().map(String::trim).toList();
  }

  private Converter<Jwt, AbstractAuthenticationToken> authConverter() {
    return jwt -> {
      try {
        AuthPrincipal principal =
            new AuthPrincipal(
                UUID.fromString(requireClaim(jwt, JwtClaimNames.SUB)),
                UUID.fromString(requireClaim(jwt, TokenClaims.ORGANIZATION)),
                jwt.getClaimAsString(TokenClaims.KIND));
        return new UsernamePasswordAuthenticationToken(principal, jwt, List.of());
      } catch (IllegalArgumentException e) {
        // A token that verifies cryptographically but is missing or has a malformed
        // subject/org claim cannot become an AuthPrincipal. BearerTokenAuthenticationFilter
        // only catches AuthenticationException, so throwing that (rather than letting
        // IllegalArgumentException propagate) is what turns this into a 401 instead of an
        // unhandled 500.
        throw new InvalidBearerTokenException("JWT is missing a required claim", e);
      }
    };
  }

  private static String requireClaim(Jwt jwt, String claimName) {
    String value = jwt.getClaimAsString(claimName);
    if (value == null) {
      throw new IllegalArgumentException("Missing claim: " + claimName);
    }
    return value;
  }
}
