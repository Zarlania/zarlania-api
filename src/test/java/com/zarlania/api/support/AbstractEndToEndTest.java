package com.zarlania.api.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base for controller end-to-end tests. Boots the full application on a real port and drives it
 * over HTTP through a {@link RestTestClient} bound to that server, exercising the genuine
 * servlet/filter chain (CORS, content negotiation, bean validation, the global exception handler)
 * end to end.
 *
 * <p>Scope is the request/response contract and middleware — status codes, headers, and body shape.
 * Verifying that data was actually persisted is the service-layer integration tests' job ({@link
 * AbstractIntegrationTest}), not an e2e concern.
 *
 * <p>{@link CleanDatabaseTestExecutionListener} clears all tables after each method since real
 * requests commit their work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Pin to H2 so a SPRING_DATASOURCE_URL in the environment can't bleed into tests
// (@TestPropertySource outranks OS env vars).
@TestPropertySource(
    properties = "spring.datasource.url=jdbc:h2:mem:zarlania;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
@TestExecutionListeners(
    listeners = CleanDatabaseTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractEndToEndTest {

  @LocalServerPort private int port;

  /** Bound to the running server once the random port is known. */
  protected RestTestClient restClient;

  @BeforeEach
  void initRestClient() {
    restClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
