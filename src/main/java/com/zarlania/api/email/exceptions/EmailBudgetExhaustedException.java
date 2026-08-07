package com.zarlania.api.email.exceptions;

import com.zarlania.api.email.BudgetedEmailSender;
import com.zarlania.api.email.EmailSender;
import java.time.Duration;

/**
 * Thrown by {@link BudgetedEmailSender} when a send would exceed the service-wide outbound budget.
 *
 * <p>Distinct from whatever an {@link EmailSender} implementation throws when a provider refuses,
 * so a caller can log the two differently: a budget rejection means this service stopped itself and
 * the cap may need raising, while a provider failure means something outside it went wrong.
 */
public final class EmailBudgetExhaustedException extends RuntimeException {

  private EmailBudgetExhaustedException(String message) {
    super(message);
  }

  /**
   * States the cap that was hit rather than taking a composed string, so every throw site reads the
   * same in the logs and the numbers an operator needs to re-derive the budget are always both
   * there.
   *
   * @param limit messages allowed per window
   * @param window how long the window lasts
   */
  public static EmailBudgetExhaustedException forExhaustedBudget(int limit, Duration window) {
    return new EmailBudgetExhaustedException(
        "Outbound email budget of " + limit + " per " + window + " is exhausted");
  }
}
