package com.zarlania.api.common.email;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** Sends email through the Resend HTTP API. */
@RequiredArgsConstructor
public class ResendEmailSender implements EmailSender {

  private static final String EMAILS_PATH = "/emails";

  // Aliased deliberately, not defensively copied: RestClient is immutable once
  // built and is documented as thread-safe, so there is no mutable
  // representation here for a caller to corrupt (SpotBugs EI_EXPOSE_REP2
  // considered and rejected).
  private final RestClient restClient;
  private final String from;

  @Override
  public void send(EmailMessage message) {
    restClient
        .post()
        .uri(EMAILS_PATH)
        .body(requestBody(message))
        .retrieve()
        .onStatus(
            status -> !status.is2xxSuccessful(), (request, response) -> throwOnError(response))
        .toBodilessEntity();
  }

  private Map<String, Object> requestBody(EmailMessage message) {
    return Map.of(
        "from", from,
        "to", List.of(message.to()),
        "subject", message.subject(),
        "text", message.textBody());
  }

  // Resend's own error responses carry no detail worth surfacing to the caller,
  // so only the status is preserved. The caller (registration) maps this to a 500.
  private void throwOnError(ClientHttpResponse response) throws IOException {
    throw new IllegalStateException("Resend responded with status " + response.getStatusCode());
  }
}
