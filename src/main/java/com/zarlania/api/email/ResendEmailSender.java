package com.zarlania.api.email;

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

  /**
   * Turns any non-2xx response into a failure carrying the status and nothing else — Resend's error
   * bodies hold no detail worth surfacing.
   *
   * <p>Nothing downstream turns this into a response. Sends happen off the request thread, after
   * the work that triggered them has committed, so the caller catches this and logs it under {@code
   * EMAIL_SEND_FAILED}; answering differently when mail fails would re-open the enumeration channel
   * that off-thread dispatch exists to close.
   */
  private void throwOnError(ClientHttpResponse response) throws IOException {
    throw new IllegalStateException("Resend responded with status " + response.getStatusCode());
  }
}
