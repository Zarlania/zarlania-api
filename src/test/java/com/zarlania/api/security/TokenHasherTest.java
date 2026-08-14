package com.zarlania.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHasherTest {

  private static final String KNOWN_SHA256_OF_ABC =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  void sha256HexReturnsTheKnownDigestOfAKnownInput() {
    assertThat(TokenHasher.sha256Hex("abc")).isEqualTo(KNOWN_SHA256_OF_ABC);
  }

  @Test
  void newUrlSafeTokenReturnsDifferentValuesOnEachCall() {
    String first = TokenHasher.newUrlSafeToken();
    String second = TokenHasher.newUrlSafeToken();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void newUrlSafeTokenContainsNoUnsafeBase64Characters() {
    String token = TokenHasher.newUrlSafeToken();

    assertThat(token).doesNotContain("+", "/", "=");
  }
}
