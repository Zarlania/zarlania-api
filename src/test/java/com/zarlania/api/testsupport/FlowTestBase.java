package com.zarlania.api.testsupport;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for tests that walk a sequence of endpoints as a real client would — the {@code *FlowTest}
 * tier, where the subject is what carries from one request to the next rather than any single
 * response.
 *
 * <p>Adds the composite steps every such test starts from. Getting an account to a usable state
 * takes three requests and a round trip through an email, and a flow test that spelled that out
 * would bury the flow it is actually about under its own setup.
 */
public abstract class FlowTestBase extends EndToEndTestBase {

  /** The password every seeded account is created with, unless a test needs a different one. */
  protected static final String PASSWORD = "correct-horse-battery";

  protected AuthEndpoints auth;

  @BeforeEach
  void bindAuthEndpoints() {
    auth = new AuthEndpoints(mockMvc);
  }

  /**
   * Registers an account and verifies it, leaving it able to log in.
   *
   * <p>Goes through the emailed link rather than reaching into the database, because that round
   * trip is part of what these tests exist to prove. The link is read from the mail addressed to
   * this account rather than from whatever was sent last, so a class sharing its Spring context
   * with another cannot pick up someone else's token.
   */
  protected void registerAndVerify(String email, String username) throws Exception {
    auth.register(email, username, PASSWORD).andExpect(status().isAccepted());
    auth.verify(AuthEndpoints.verificationTokenIn(lastEmailTo(email))).andExpect(status().isOk());
  }

  /** Registers, verifies and logs in, returning the login response to read a session out of. */
  protected MvcResult registerVerifyAndLogin(String email, String username) throws Exception {
    registerAndVerify(email, username);
    return auth.login(username, PASSWORD).andExpect(status().isOk()).andReturn();
  }

  /**
   * The body of the most recent email sent to one address — the verification link, in practice.
   *
   * <p>By recipient rather than "the last thing sent", so this stays correct whatever else is using
   * the same recorder.
   */
  protected String lastEmailTo(String address) {
    return recordedEmails.messagesTo(address).getLast().textBody();
  }

  /**
   * The body of the most recently sent email, whoever it went to.
   *
   * <p>Only safe in a class that owns its Spring context; prefer {@link #lastEmailTo}.
   */
  protected String lastEmailBody() {
    return recordedEmails.messages().getLast().textBody();
  }
}
