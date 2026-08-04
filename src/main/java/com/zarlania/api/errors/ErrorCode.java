package com.zarlania.api.errors;

/**
 * One machine-readable error the API can answer with: the stable {@code code} string clients branch
 * on, and the HTTP status it is returned under.
 *
 * <p>An interface rather than one central enum, so each domain owns the codes it can produce and
 * carries them out of the monolith with it. Implementations are enums — {@code AuthErrorCode},
 * {@code ThrottleErrorCode}, {@link ValidationErrorCode} — which keeps a domain's whole error
 * vocabulary readable in one place while leaving {@link ProblemDetails} able to render any of them.
 *
 * <p>Every code string is published contract: {@code zarlania-app} matches these exact strings, so
 * a shipped one must never change. Adding a code is safe; renaming one is not.
 */
public interface ErrorCode {

  /** The stable string clients branch on, such as {@code auth.username-taken}. */
  String getCode();

  /** The HTTP status this code is answered under. */
  int getStatus();
}
