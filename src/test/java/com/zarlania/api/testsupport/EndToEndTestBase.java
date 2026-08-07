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
 * <p>Deliberately does <em>not</em> clear recorded email before each test. {@link
 * RecordingEmailSender} is a singleton per Spring context, and a context is shared by every class
 * configured identically — so a blanket clear here would let a class that never looks at email wipe
 * the inbox of one that is midway through reading it. Read your own mail by recipient instead
 * ({@link RecordingEmailSender#messagesTo}); clear only in a class that owns its context and needs
 * to assert on total volume.
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
