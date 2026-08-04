package com.zarlania.api.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit-level: reflection over plain records, no context and no request. */
class AccountIdentifierReaderTest {

  @Test
  void readsTheNamedComponentFromTheOnlyRecordArgument() {
    Object[] arguments = {new LoginBody("bob@example.com", "hunter2")};

    assertThat(AccountIdentifierReader.read(arguments, "identifier")).isEqualTo("bob@example.com");
  }

  // Handlers take more than the body — a cookie value, a CsrfToken — so the reader has to pick the
  // argument that actually declares the component rather than assuming a position.
  @Test
  void skipsArgumentsThatAreNotRecordsAndOnesWithoutTheComponent() {
    Object[] arguments = {"a-cookie-value", new LoginBody("bob@example.com", "hunter2")};

    assertThat(AccountIdentifierReader.read(arguments, "identifier")).isEqualTo("bob@example.com");
  }

  @Test
  void readsWhicheverComponentTheEndpointNames() {
    Object[] arguments = {new RegisterBody("bob@example.com", "bob")};

    assertThat(AccountIdentifierReader.read(arguments, "email")).isEqualTo("bob@example.com");
    assertThat(AccountIdentifierReader.read(arguments, "username")).isEqualTo("bob");
  }

  @Test
  void aNullArgumentIsSkippedRatherThanFailing() {
    Object[] arguments = {null, new LoginBody("bob@example.com", "hunter2")};

    assertThat(AccountIdentifierReader.read(arguments, "identifier")).isEqualTo("bob@example.com");
  }

  // Failing loudly matters more than degrading here: silently skipping the account bucket would
  // leave the endpoint throttled per IP only, which is the exact gap the account bucket exists to
  // close. ThrottledEndpointConventionTest is what stops this reaching production at all.
  @Test
  void aComponentNoArgumentDeclaresIsAnError() {
    Object[] arguments = {new LoginBody("bob@example.com", "hunter2")};

    assertThatThrownBy(() -> AccountIdentifierReader.read(arguments, "email"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("email");
  }

  @Test
  void noArgumentsAtAllIsAnError() {
    assertThatThrownBy(() -> AccountIdentifierReader.read(new Object[0], "identifier"))
        .isInstanceOf(IllegalStateException.class);
  }
}
