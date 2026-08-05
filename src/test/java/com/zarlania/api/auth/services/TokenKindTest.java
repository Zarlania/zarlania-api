package com.zarlania.api.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The {@code kind} claim's two directions: what this service writes, and what it accepts back.
 *
 * <p>The wire string is pinned here because it is published contract — a verifier outside this
 * repository reads it — so a constant rename must fail this test rather than silently change what
 * appears in every token.
 */
class TokenKindTest {

  @Test
  void theUserKindIsWrittenToTheClaimInLowercase() {
    assertThat(TokenKind.USER.value()).isEqualTo("user");
  }

  @Test
  void aKnownClaimValueResolvesBackToItsKind() {
    assertThat(TokenKind.fromValue("user")).isEqualTo(TokenKind.USER);
  }

  // An inbound claim is caller-controlled text, so every shape that is not exactly a known value
  // has to be refused rather than folded onto USER — matching on the constant name, or on a
  // different case, would let a token this service never minted authenticate as a user session.
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"USER", "User", "service", "", " user"})
  void anythingElseIsRefusedRatherThanTreatedAsAUserToken(String claimValue) {
    assertThatThrownBy(() -> TokenKind.fromValue(claimValue))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
