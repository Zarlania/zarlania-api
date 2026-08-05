package com.zarlania.api.email;

import com.zarlania.api.email.exceptions.EmailBudgetExhaustedException;
import com.zarlania.api.throttle.RateLimiter;
import java.time.Duration;
import lombok.RequiredArgsConstructor;

/**
 * Caps how much mail the whole service can send in a period, wrapping whichever {@link EmailSender}
 * is configured.
 *
 * <p>The per-caller throttles do not bound this. Register allows 5/min per client IP, which is
 * roughly 7,200 messages a day from a single compliant address against a Resend free tier of about
 * 100 — so without a global cap the quota is spent by lunchtime, and every legitimate verification
 * email after that silently fails to arrive. A budget makes the ceiling explicit and, because the
 * rejection is thrown rather than swallowed, visible in the logs when it is reached.
 *
 * <p>One fixed key for the entire service, deliberately: this is a cap on total outbound volume,
 * not another per-caller limit.
 */
@RequiredArgsConstructor
public class BudgetedEmailSender implements EmailSender {

  private static final String BUDGET_KEY = "email:global";

  private final EmailSender delegate;
  private final RateLimiter rateLimiter;
  private final int budgetLimit;
  private final Duration budgetWindow;

  @Override
  public void send(EmailMessage message) {
    if (!rateLimiter.tryConsume(BUDGET_KEY, budgetLimit, budgetWindow).allowed()) {
      throw EmailBudgetExhaustedException.forExhaustedBudget(budgetLimit, budgetWindow);
    }
    delegate.send(message);
  }
}
