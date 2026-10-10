package com.accubook.trade;

/**
 * A posted invoice's settlement position, derived - never stored (R-7).
 *
 * <p>Total, as the reference's {@code deriveSettlementStatus} rightly is: every
 * input maps to exactly one status, so it never depends on what a document
 * happened to read before - a reversed receipt cannot leave an invoice PAID.
 */
public enum SettlementStatus {
    OPEN, PARTIAL, OVERDUE, SETTLED;

    public static SettlementStatus of(java.math.BigDecimal total, java.math.BigDecimal outstanding,
                                      java.time.LocalDate dueDate, java.time.LocalDate today) {
        if (outstanding.signum() <= 0) {
            return SETTLED;
        }
        boolean overdue = dueDate != null && dueDate.isBefore(today);
        if (overdue) {
            return OVERDUE;
        }
        return outstanding.compareTo(total) < 0 ? PARTIAL : OPEN;
    }
}
