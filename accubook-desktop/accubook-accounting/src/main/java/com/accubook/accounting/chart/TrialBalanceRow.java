package com.accubook.accounting.chart;

import java.math.BigDecimal;

/**
 * One line of a trial balance.
 *
 * <p>Debit and credit are kept as separate columns rather than one signed
 * figure, because that is how a trial balance is read and how its totals are
 * checked. A ledger nets to whichever side its nature and activity put it on;
 * the other column is zero.
 *
 * <p>{@code active} is carried so the report can mark an account the customer
 * has deactivated. Such an account still appears whenever it holds a balance -
 * hiding it would make the two columns disagree, which is worse than an
 * unfamiliar name on the report.
 */
public record TrialBalanceRow(
        String ledgerId,
        String ledgerName,
        AccountNature nature,
        BigDecimal debit,
        BigDecimal credit,
        boolean active) {

    /** True when this account has no movement and no opening balance. */
    public boolean isZero() {
        return debit.signum() == 0 && credit.signum() == 0;
    }
}
