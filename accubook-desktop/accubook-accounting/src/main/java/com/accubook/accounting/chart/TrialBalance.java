package com.accubook.accounting.chart;

import java.math.BigDecimal;
import java.util.List;

/**
 * A fiscal year's trial balance.
 *
 * <p>{@link #balances()} is the whole point of the report. Because every voucher
 * balances and the schema enforces it with
 * {@code CHECK (total_debit_e4 = total_credit_e4)}, an imbalance here is a
 * defect in the software rather than a data-entry mistake - there is no rounding
 * slack for it to hide in at scaled-integer precision.
 */
public record TrialBalance(
        String fiscalYearId,
        List<TrialBalanceRow> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit) {

    public TrialBalance {
        rows = List.copyOf(rows);
    }

    /** True when total debits equal total credits. Must always hold. */
    public boolean balances() {
        return totalDebit.compareTo(totalCredit) == 0;
    }

    /** Rows with any movement, for a report that hides empty accounts. */
    public List<TrialBalanceRow> nonZeroRows() {
        return rows.stream().filter(row -> !row.isZero()).toList();
    }

    /** The difference, for a diagnostic message when something has gone wrong. */
    public BigDecimal difference() {
        return totalDebit.subtract(totalCredit).abs();
    }
}
