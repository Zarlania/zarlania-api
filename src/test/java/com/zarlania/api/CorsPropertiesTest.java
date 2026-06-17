package com.zarlania.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorsPropertiesTest {

  @Test
  void acceptsExplicitOriginsAndStoresImmutableCopy() {
    List<String> source = new ArrayList<>(List.of("https://zarlania.com"));
    CorsProperties props = new CorsProperties(source);

    source.add("https://mutated.example.com");
    assertEquals(List.of("https://zarlania.com"), props.allowedOrigins());
    assertThrows(
        UnsupportedOperationException.class,
        () -> props.allowedOrigins().add("https://nope.example.com"));
  }

  @Test
  void rejectsNullList() {
    assertThrows(IllegalArgumentException.class, () -> new CorsProperties(null));
  }

  @Test
  void rejectsEmptyList() {
    assertThrows(IllegalArgumentException.class, () -> new CorsProperties(List.of()));
  }

  @Test
  void rejectsBlankOrigin() {
    List<String> origins = List.of("   ");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> new CorsProperties(origins));
    assertTrue(ex.getMessage().contains("blank"));
  }

  @Test
  void rejectsNullOrigin() {
    List<String> origins = Arrays.asList("https://zarlania.com", null);
    assertThrows(IllegalArgumentException.class, () -> new CorsProperties(origins));
  }

  @Test
  void rejectsWildcardOrigin() {
    List<String> origins = List.of(" * ");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> new CorsProperties(origins));
    assertTrue(ex.getMessage().contains("'*'"));
  }
}
