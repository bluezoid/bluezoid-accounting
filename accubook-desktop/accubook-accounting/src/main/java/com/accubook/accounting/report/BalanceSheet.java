package com.accubook.accounting.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The balance sheet as of a date, from every posted voucher up to it. Profit
 * not yet closed to Retained Earnings is shown in equity as the profit for the
 * open period. Assets equal liabilities plus equity, or {@link #balances()}
 * says so.
 */
public record BalanceSheet(LocalDate asOf, List<StatementLine> assets, List<StatementLine> liabilities,
                           List<StatementLine> equity, BigDecimal unclosedProfit, BigDecimal totalAssets,
                           BigDecimal totalLiabilitiesAndEquity) {

    public BalanceSheet {
        assets = List.copyOf(assets);
        liabilities = List.copyOf(liabilities);
        equity = List.copyOf(equity);
    }

    public boolean balances() {
        return totalAssets.compareTo(totalLiabilitiesAndEquity) == 0;
    }
}
