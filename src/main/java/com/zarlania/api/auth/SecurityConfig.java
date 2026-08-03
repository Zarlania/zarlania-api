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
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Wires the resource-server filter chain: every request needs a valid JWT except the public paths
 * below, and a valid JWT is turned into an {@link AuthPrincipal} rather than the default
 * scope-based {@code Jwt} authentication.
 */
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

  // The only two routes on this service that authenticate with a cookie instead of a bearer
  // token, and therefore the only two with a CSRF surface at all. See configureCsrf.
  private static final String REFRESH_PATH = "/auth/refresh";
  private static final String LOGOUT_PATH = "/auth/logout";

  // Must match AuthController's REFRESH_COOKIE_PATH and SAME_SITE_STRICT: the CSRF cookie and the
  // refresh cookie are two halves of one defence and have to travel together, so a request that
  // carries one always carries the other. Commented at both ends rather than extracted, since two
  // occurrences is not yet an abstraction.
  private static final String CSRF_COOKIE_PATH = "/auth";
  private static final String SAME_SITE_STRICT = "Strict";

  private static final String CORS_ALL_PATHS_PATTERN = "/**";
  private static final List<String> CORS_ALLOWED_METHODS =
      List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS");
  // CookieCsrfTokenRepository's default header name. It has to be allow-listed for CORS or the
  // browser's preflight refuses the very header the CSRF check requires, and POST /auth/refresh
  // would fail for the legitimate client while looking like a security decision. Clients read the
  // name off GET /auth/csrf rather than hardcoding it, so this literal is the single source.
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  private static final List<String> CORS_ALLOWED_HEADERS =
      List.of("Authorization", "Content-Type", CSRF_HEADER);

  private final JwtKeys jwtKeys;
  private final AuthProperties authProperties;

  /**
   * The one filter chain: every request needs a valid JWT except {@link #PUBLIC_PATHS}, sessions
   * are never created, and CSRF is scoped to the two cookie-authenticated routes.
   */
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
    http.csrf(this::configureCsrf)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(authConverter())));
    return http.build();
  }

  /**
   * Verifies access tokens against this service's own key set, and pins the issuer.
   *
   * <p>{@code createDefaultWithIssuer}, not the default validator: the default checks only that the
   * token has not expired. Pinning {@code iss} means a token minted by some other issuer that
   * happens to verify against a key in this set — the case a future shared or mistakenly reused key
   * would create — is rejected rather than accepted as one of ours.
   */
  @Bean
  public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(jwtKeys.publicJwkSet())).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(authProperties.issuer()));
    return decoder;
  }

  /**
   * Allows the browser client's origin, with credentials, so the refresh cookie can travel.
   *
   * @param allowedOrigins comma-separated and always an explicit list, never a wildcard — Spring
   *     rejects the wildcard paired with credentials at startup, which is the check that keeps this
   *     safe
   */
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

  /**
   * Scoped, not disabled. Every route on this service except two authenticates with a bearer token
   * in the Authorization header, and a browser never attaches that by itself — a forged cross-site
   * request arrives with no credential at all, so there is nothing there to protect and demanding a
   * CSRF token would only burden clients that face no risk. The exceptions are POST /auth/refresh
   * and POST /auth/logout, which authenticate with the zarlania_refresh *cookie*; that the browser
   * does attach automatically, which makes those two the service's entire CSRF surface and the only
   * two the token check guards.
   *
   * <p>The token is defence in depth rather than the first line: the refresh cookie is already
   * SameSite=Strict, so a browser withholds it on cross-site requests to begin with, and the CORS
   * allow-list is explicit rather than a wildcard. This closes what those leave open — a same-site-
   * but-untrusted origin (any other host under the registrable domain), and browsers or extensions
   * where SameSite is not honoured as advertised.
   */
  private void configureCsrf(CsrfConfigurer<HttpSecurity> csrf) {
    csrf.csrfTokenRepository(csrfTokenRepository())
        .requireCsrfProtectionMatcher(
            new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, REFRESH_PATH),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGOUT_PATH)));
  }

  /**
   * Double-submit: the server sets the token in a cookie, the client echoes it back in a header,
   * and only a caller able to obtain both can produce a matching pair — an attacker's page can make
   * the browser send the cookie but cannot read it to build the header.
   *
   * <p>HttpOnly stays on, which is where this departs from the usual SPA recipe. That recipe has
   * JavaScript read the cookie with document.cookie, which cannot work here: the browser client is
   * served from zarlania.com while this API sets cookies for api.zarlania.com, and document.cookie
   * is scoped by host, not by site. GET /auth/csrf hands the client the value over CORS instead, so
   * the cookie can stay unreadable to script entirely — which is strictly better, because it also
   * puts the token out of reach of an XSS on any sibling host.
   */
  private CookieCsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
    repository.setCookiePath(CSRF_COOKIE_PATH);
    repository.setCookieCustomizer(
        cookie -> cookie.secure(authProperties.cookieSecure()).sameSite(SAME_SITE_STRICT));
    return repository;
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
