package com.zarlania.api.auth;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * Accepts a CSRF token only from the {@code X-XSRF-TOKEN} header, never from a {@code _csrf}
 * request parameter.
 *
 * <p>Spring's own handlers take either, which is right for a server-rendered form and wrong here.
 * The whole reason this service checks CSRF tokens is the sibling-host case {@code SecurityConfig}
 * describes: a compromised page on any other host under {@code zarlania.com} is <em>same-site</em>,
 * so {@code SameSite=Strict} still lets it send the refresh cookie. That page can also set a
 * parent-domain {@code XSRF-TOKEN} cookie of its own choosing before the client has ever called
 * {@code GET /auth/csrf}, then POST a plain HTML form to {@code /auth/refresh} carrying a matching
 * {@code _csrf} field — and the double-submit comparison passes. No preflight is involved, and
 * nothing had to read the HttpOnly cookie.
 *
 * <p>Requiring the header closes that, because a plain form cannot set one. Anything that can set a
 * header is doing so from script, which means the browser applies CORS, and this service's
 * allow-list names one origin.
 */
final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

  // The masking handler Spring installs by default, kept for its BREACH protection: it XORs each
  // issued token against fresh randomness and un-XORs it on the way back in, so two reads of the
  // same session's token never produce the same string. It is final, so this delegates rather than
  // extends, and resolution below runs it only once the header has been established.
  private final XorCsrfTokenRequestAttributeHandler delegate =
      new XorCsrfTokenRequestAttributeHandler();

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
    delegate.handle(request, response, csrfToken);
  }

  /**
   * @return the token the caller sent in the header, un-masked, or null when the header is absent —
   *     which {@code CsrfFilter} treats as a failed check. Guarding on the header before delegating
   *     is what keeps the parameter out of it: the delegate would fall back to the parameter, and
   *     un-masking is private to it, so there is nothing to reuse other than this ordering.
   */
  @Override
  @SuppressFBWarnings(
      value = "SERVLET_HEADER",
      justification =
          "The header is read for presence only, and the value is never trusted: it is"
              + " handed straight to CsrfFilter, which grants nothing until it matches the"
              + " token this server set in a cookie the caller cannot read. That a client can"
              + " set the header freely is the premise of double-submit, not a flaw in it —"
              + " and refusing the request when the header is absent is what this method adds.")
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    if (request.getHeader(csrfToken.getHeaderName()) == null) {
      return null;
    }
    return delegate.resolveCsrfTokenValue(request, csrfToken);
  }
}
