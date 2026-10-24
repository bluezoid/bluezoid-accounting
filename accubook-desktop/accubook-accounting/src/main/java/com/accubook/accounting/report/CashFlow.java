package com.accubook.accounting.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cash flow by the direct method (P11-2): every movement of the Cash &amp; Bank
 * ledgers, classified by the ledger it moved against. Transfers between cash and
 * bank are not flows. Opening cash plus the net flow is closing cash, or
 * {@link #reconciles()} says so.
 */
public record CashFlow(LocalDate from, LocalDate to, BigDecimal openingCash, List<Flow> operating,
                       List<Flow> investing, List<Flow> financing, BigDecimal closingCash) {

    /** Money in positive, out negative, against one group. */
    public record Flow(String groupName, BigDecimal amount) {
    }

    public CashFlow {
        operating = List.copyOf(operating);
        investing = List.copyOf(investing);
        financing = List.copyOf(financing);
    }

    public BigDecimal net() {
        return total(operating).add(total(investing)).add(total(financing));
    }

    public static BigDecimal total(List<Flow> flows) {
        return flows.stream().map(Flow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean reconciles() {
        return openingCash.add(net()).compareTo(closingCash) == 0;
    }
}
