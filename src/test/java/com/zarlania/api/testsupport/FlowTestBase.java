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
   * trip is part of what these tests exist to prove. Recorded email is cleared afterwards, so a
   * test asserting on what was sent sees only what its own flow sent.
   */
  protected void registerAndVerify(String email, String username) throws Exception {
    auth.register(email, username, PASSWORD).andExpect(status().isAccepted());
    auth.verify(AuthEndpoints.verificationTokenIn(lastEmailBody())).andExpect(status().isOk());
    recordedEmails.clear();
  }

  /** Registers, verifies and logs in, returning the login response to read a session out of. */
  protected MvcResult registerVerifyAndLogin(String email, String username) throws Exception {
    registerAndVerify(email, username);
    return auth.login(username, PASSWORD).andExpect(status().isOk()).andReturn();
  }

  /** The body of the most recently sent email — the verification link, in practice. */
  protected String lastEmailBody() {
    return recordedEmails.messages().getLast().textBody();
  }
}
