package com.zarlania.api.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

  private static final String RESEND_BASE_URL = "https://api.resend.com";
  private static final String EMAILS_PATH = RESEND_BASE_URL + "/emails";
  private static final String FROM_ADDRESS = "no-reply@zarlania.com";
  private static final String API_KEY = "re_test_key";

  private static RestClient.Builder authorizedBuilder() {
    return RestClient.builder()
        .baseUrl(RESEND_BASE_URL)
        .defaultHeader("Authorization", "Bearer " + API_KEY);
  }

  @Test
  void sendPostsToResendEmailsEndpointWithBearerAuthAndJsonBody() {
    RestClient.Builder builder = authorizedBuilder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ResendEmailSender sender = new ResendEmailSender(builder.build(), FROM_ADDRESS);

    server
        .expect(requestTo(EMAILS_PATH))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer " + API_KEY))
        .andExpect(jsonPath("$.from").value(FROM_ADDRESS))
        .andExpect(jsonPath("$.to[0]").value("someone@example.com"))
        .andExpect(jsonPath("$.subject").value("Verify your email"))
        .andExpect(jsonPath("$.text").value("Click the link to verify."))
        .andRespond(withSuccess("{\"id\":\"abc123\"}", MediaType.APPLICATION_JSON));

    sender.send(
        new EmailMessage("someone@example.com", "Verify your email", "Click the link to verify."));

    server.verify();
  }

  @Test
  void sendThrowsIllegalStateExceptionCarryingTheStatusOnAServerError() {
    RestClient.Builder builder = authorizedBuilder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ResendEmailSender sender = new ResendEmailSender(builder.build(), FROM_ADDRESS);

    server.expect(requestTo(EMAILS_PATH)).andRespond(withServerError());

    EmailMessage message =
        new EmailMessage("someone@example.com", "Verify your email", "Click the link to verify.");

    assertThatThrownBy(() -> sender.send(message))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500");

    server.verify();
  }

  @Test
  void sendThrowsIllegalStateExceptionOnARedirectBecauseOnlyA2xxCountsAsSuccess() {
    // The adapter's contract is "non-2xx throws" (not merely "4xx/5xx throws"),
    // so a 3xx — which RestClient's own error handling would otherwise let
    // through — must be rejected here too.
    RestClient.Builder builder = authorizedBuilder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    ResendEmailSender sender = new ResendEmailSender(builder.build(), FROM_ADDRESS);

    server.expect(requestTo(EMAILS_PATH)).andRespond(withStatus(HttpStatus.FOUND));

    EmailMessage message =
        new EmailMessage("someone@example.com", "Verify your email", "Click the link to verify.");

    assertThatThrownBy(() -> sender.send(message))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("302");

    server.verify();
  }
}
