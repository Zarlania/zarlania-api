package com.zarlania.api.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Base for tests whose subject is transactional behaviour itself — locking, rollback boundaries,
 * and what two concurrent callers observe of each other — the {@code *TransactionTest} tier.
 *
 * <p>Separate from {@link IntegrationTestBase} because these tests differ in kind, not only in what
 * they assert. They deliberately provoke races, so they must not run alongside anything else
 * touching the same rows. {@code SAME_THREAD} plus an exclusive lock on the global resource keeps
 * them serial even once the rest of the suite runs in parallel — the point being that the decision
 * is recorded here, once, rather than rediscovered the day parallelism is switched on.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = Resources.GLOBAL, mode = ResourceAccessMode.READ_WRITE)
public abstract class TransactionTestBase extends IntegrationTestBase {

  private static final int RACERS = 2;
  private static final int READY_TIMEOUT_SECONDS = 5;
  private static final int RESULT_TIMEOUT_SECONDS = 10;

  /**
   * Releases two callables at the same instant on separate threads and returns both results, as a
   * fixed two-element list.
   *
   * <p>Both threads wait on one latch rather than simply being submitted, because submitting two
   * tasks lets the first finish before the second starts on a busy machine — precisely the ordering
   * these tests are not interested in.
   *
   * <p>Neither exception nor timeout is swallowed: either fails the test, which is what a deadlock
   * (surfaced as {@code CannotAcquireLockException}) or a genuine hang must do. {@link
   * Arrays#asList} rather than {@link List#of}, since a callable's result may legitimately be
   * {@code null}.
   */
  protected static <T> List<T> raceTwo(Callable<T> first, Callable<T> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(RACERS);
    CountDownLatch ready = new CountDownLatch(RACERS);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<T> futureFirst = executor.submit(gatedBy(ready, start, first));
      Future<T> futureSecond = executor.submit(gatedBy(ready, start, second));
      assertThat(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      T resultFirst = futureFirst.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      T resultSecond = futureSecond.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return Arrays.asList(resultFirst, resultSecond);
    } finally {
      executor.shutdown();
    }
  }

  private static <T> Callable<T> gatedBy(
      CountDownLatch ready, CountDownLatch start, Callable<T> task) {
    return () -> {
      ready.countDown();
      start.await();
      return task.call();
    };
  }
}
