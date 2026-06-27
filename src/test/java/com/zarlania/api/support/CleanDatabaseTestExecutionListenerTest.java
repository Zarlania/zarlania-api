package com.zarlania.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.users.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves committed rows don't leak between test methods. Both methods use the <em>same</em> fixed
 * email and each asserts the table is empty before creating it: order-independent, so whichever
 * runs second would see the other's leaked row and fail if {@link
 * CleanDatabaseTestExecutionListener} stopped truncating.
 */
@SpringBootTest
class CleanDatabaseTestExecutionListenerTest extends AbstractIntegrationTest {

  private static final String PROBE_EMAIL = "leak-probe@example.com";
  private static final String PROBE_USERNAME = "leakprobe";

  @Autowired private UserService userService;

  @Test
  void firstMethodStartsCleanAndCommits() {
    assertStartsCleanThenCreate();
  }

  @Test
  void secondMethodAlsoStartsClean() {
    assertStartsCleanThenCreate();
  }

  private void assertStartsCleanThenCreate() {
    assertThat(userService.findByEmail(PROBE_EMAIL)).isEmpty();
    userService.create(PROBE_EMAIL, PROBE_USERNAME);
  }
}
