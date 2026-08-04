package com.zarlania.api.email.exceptions;

import com.zarlania.api.email.BudgetedEmailSender;
import com.zarlania.api.email.EmailSender;

/**
 * Thrown by {@link BudgetedEmailSender} when a send would exceed the service-wide outbound budget.
 *
 * <p>Distinct from whatever an {@link EmailSender} implementation throws when a provider refuses,
 * so a caller can log the two differently: a budget rejection means this service stopped itself and
 * the cap may need raising, while a provider failure means something outside it went wrong.
 */
public class EmailBudgetExhaustedException extends RuntimeException {

  /**
   * @param message states the budget and window that were exhausted, since this is read in logs
   */
  public EmailBudgetExhaustedException(String message) {
    super(message);
  }
}
