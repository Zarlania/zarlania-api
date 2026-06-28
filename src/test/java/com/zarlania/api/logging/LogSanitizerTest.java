package com.zarlania.api.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

  @Test
  void collapsesForgedSecondLineIntoOneLine() {
    // A CRLF in a logged value would otherwise inject a forged "INFO ..." log line.
    assertThat(LogSanitizer.forLog("real\r\nINFO forged-line")).isEqualTo("realINFO forged-line");
  }

  @Test
  void removesEmbeddedNewlinesWhileKeepingOtherText() {
    assertThat(LogSanitizer.forLog("alice\nbob")).isEqualTo("alicebob");
    assertThat(LogSanitizer.forLog("alice\rbob")).isEqualTo("alicebob");
  }

  @Test
  void leavesCleanValuesUnchanged() {
    UUID id = UUID.randomUUID();
    assertThat(LogSanitizer.forLog(id)).isEqualTo(id.toString());
    assertThat(LogSanitizer.forLog("This username is already taken"))
        .isEqualTo("This username is already taken");
  }

  @Test
  void isNullSafe() {
    assertThat(LogSanitizer.forLog(null)).isEqualTo("null");
  }
}
