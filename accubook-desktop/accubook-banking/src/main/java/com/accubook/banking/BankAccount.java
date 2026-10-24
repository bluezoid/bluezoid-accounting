package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A Cash &amp; Bank ledger's bank details. It has no balance of its own: the
 * ledger's is the balance (the reference keeps a second one that drifts).
 *
 * @param type               CURRENT, SAVINGS, CASH_CREDIT or OVERDRAFT
 * @param reconcileFrom      reconciliation starts on this date (P10-5), or null until set
 * @param openingStatement   the statement balance at the end of the day before; negative when overdrawn
 */
public record BankAccount(String ledgerId, String ledgerName, String bankName, String branch, String accountNumber,
                          String ifsc, String type, LocalDate reconcileFrom, BigDecimal openingStatement) {
}
