package com.accubook.accounting.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Profit and loss for a period, in trading form: sales less direct expenses
 * (purchases, the change in stock) is the gross profit; other income less
 * indirect expenses brings it to the net profit. Year-end closing vouchers are
 * left out, so a closed year shows its trading, not nil.
 */
public record ProfitAndLoss(LocalDate from, LocalDate to, List<StatementLine> tradingIncome,
                            List<StatementLine> directExpenses, BigDecimal grossProfit,
                            List<StatementLine> otherIncome, List<StatementLine> indirectExpenses,
                            BigDecimal netProfit) {

    public ProfitAndLoss {
        tradingIncome = List.copyOf(tradingIncome);
        directExpenses = List.copyOf(directExpenses);
        otherIncome = List.copyOf(otherIncome);
        indirectExpenses = List.copyOf(indirectExpenses);
    }
}
