package com.accubook.accounting.report;

import java.math.BigDecimal;

/**
 * One line of a financial statement: a ledger's amount under its heading.
 *
 * @param amount positive on the statement's natural side - a debit for assets and
 *               expenses, a credit for liabilities, equity and income
 */
public record StatementLine(String heading, String ledgerId, String ledgerName, BigDecimal amount) {
}
