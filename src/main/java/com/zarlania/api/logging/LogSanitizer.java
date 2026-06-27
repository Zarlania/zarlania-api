package com.zarlania.api.logging;

/**
 * Strips CR/LF from values before they are written to logs, preventing log forging (CWE-117), where
 * a newline embedded in user-influenced text could inject a forged log line. Centralised so every
 * domain sanitises logged values the same way; routing values through here is what keeps the
 * build's {@code CRLF_INJECTION_LOGS} detector satisfied — the detector stays fully enabled rather
 * than excluded.
 */
public final class LogSanitizer {

  private LogSanitizer() {}

  /**
   * Renders {@code value} for a log message with carriage returns and line feeds removed.
   * Null-safe: a null value renders as the literal {@code "null"}.
   *
   * @param value the value to log (any type; rendered via {@link String#valueOf(Object)})
   * @return the value's string form with CR and LF removed
   */
  public static String forLog(Object value) {
    return String.valueOf(value).replace("\r", "").replace("\n", "");
  }
}
