package com.zarlania.api.logging;

/**
 * Makes a caller-supplied value safe to interpolate into a log line.
 *
 * <p>A log is append-only text where one event is one line, and every reader — a person grepping, a
 * shipper parsing, an alert matching — relies on that. A value that reached the process as request
 * input can contain {@code \r\n}, and interpolating it unchanged lets whoever supplied it append
 * lines of their own: a forged {@code ERROR}, a fabricated audit entry, a line attributing an
 * action to someone else. The log then says whatever its subject wanted it to say, which is worse
 * than having no log at all.
 *
 * <p>Its own topic package rather than a private copy per class, because there were three of those
 * before this existed and a fourth was about to appear. A sanitizer that is nearly the same in four
 * places is one that eventually differs in one of them, and the one that differs is the hole.
 *
 * <p>Not needed for a {@link java.util.UUID}: its {@code toString()} is hex digits and hyphens by
 * RFC 4122, so there is no injectable character to remove. Prefer logging an id over logging text
 * for that reason as much as for privacy.
 */
public final class LogSanitizer {

  private LogSanitizer() {}

  /**
   * Folds a value onto one line, so it cannot end the log entry it appears in.
   *
   * <p>Carriage returns are dropped and newlines become spaces, rather than both being dropped, so
   * that a value which was genuinely multi-line stays readable instead of running its words
   * together.
   *
   * @param value any caller-supplied text; must not be null
   * @return the same text with no line break in it
   */
  public static String singleLine(String value) {
    return value.replace("\r", "").replace("\n", " ");
  }
}
