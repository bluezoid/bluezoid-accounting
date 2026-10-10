package com.accubook.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The value of all stock on a date, read from the persisted valuation ledger (A-1). */
public record StockValuation(LocalDate asOf, List<ValuationLine> lines, BigDecimal total) {

    public StockValuation {
        lines = List.copyOf(lines);
    }
}
