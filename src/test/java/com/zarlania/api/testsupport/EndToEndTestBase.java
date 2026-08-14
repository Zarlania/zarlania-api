package com.zarlania.api.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for tests that drive the application over HTTP — the {@code *EndToEndTest} tier, which
 * covers one endpoint's request and response shape and the middleware a request passes through on
 * the way, and the {@code *FlowTest} tier built on it.
 *
 * <p>Holds what every such test needs and none of them should re-declare: {@link MockMvc}, the
 * recording email sender in place of a real provider, and the JSON request builder.
 *
 * <p>Does not clear recorded email before each test, because there is nothing left to clear: {@link
 * IntegrationTestBase} dirties the context after every method, so each method starts with a {@link
 * RecordingEmailSender} that has recorded only what that method caused. Asserting on total outbound
 * volume with {@link RecordingEmailSender#messages()} is therefore safe anywhere; {@link
 * RecordingEmailSender#messagesTo} is for reading one account's mail out of a method that sends to
 * several.
 *
 * <p>The rule that follows from a fresh context is that a test sets up the email, time and database
 * state it goes on to assert on. Nothing arrives from a previous method — but nothing is cleaned up
 * by one either, and committed rows do outlive the context, so a test that needs an account seeds
 * it under a slug of its own.
 */
@AutoConfigureMockMvc
@Import(RecordingEmailSenderConfig.class)
public abstract class EndToEndTestBase extends IntegrationTestBase {

  @Autowired protected MockMvc mockMvc;

  @Autowired protected RecordingEmailSender recordedEmails;

  /**
   * A JSON POST to {@code path}, ready for a test to add headers or a cookie to.
   *
   * @param json the body, written inline in the test so the request the client actually sends is
   *     visible rather than assembled from an object graph
   */
  protected static MockHttpServletRequestBuilder postJson(String path, String json) {
    return post(path).contentType(MediaType.APPLICATION_JSON).content(json);
  }
}
