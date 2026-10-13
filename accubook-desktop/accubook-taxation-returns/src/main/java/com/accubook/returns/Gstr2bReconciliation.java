package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Purchase documents in the books against a month's GSTR-2B (P9d-3).
 *
 * <p>Books documents considered: posted purchase bills and debit notes from
 * vendors with a GSTIN, dated up to the month's end, not already matched in an
 * earlier month's 2B. They are keyed by vendor GSTIN and the supplier's own
 * document number, compared normalised (P9-18). Figures within ₹1 of each other
 * match (P9-17).
 */
public record Gstr2bReconciliation(ReturnPeriod period, String importId, List<Row> rows) {

    public Gstr2bReconciliation {
        rows = List.copyOf(rows);
    }

    public enum Status {
        /** In both, every figure within ₹1, same date. */
        MATCHED,
        /** In both, but a figure or the date differs. */
        MISMATCHED,
        /** The supplier reported it; the books do not have it. Its credit waits for the bill to be entered. */
        ONLY_IN_2B,
        /** In the books; the supplier has not reported it. Its credit is deferred (P9-19). */
        ONLY_IN_BOOKS
    }

    public record Figures(BigDecimal value, BigDecimal taxable, BigDecimal igst, BigDecimal cgst, BigDecimal sgst,
                          BigDecimal cess) {
        public static final Figures NONE = new Figures(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * @param type          {@code INV} for a bill, {@code CN} for a supplier's credit note (our debit note)
     * @param documentId    the purchase document in the books, or null
     * @param exactNumber   the numbers matched as written, not only once normalised
     * @param differences   what differs, in words, for a mismatch
     * @param probableMatch for an unmatched row, the other side's document of the same
     *                      supplier with the same value, if there is exactly one
     */
    public record Row(Status status, String type, String ctin, String supplierName, String number,
                      LocalDate date, String documentId, Figures books, Figures gstr2b, boolean reverseCharge,
                      boolean itcAvailable, boolean exactNumber, List<String> differences, String probableMatch) {

        public Row {
            differences = List.copyOf(differences);
        }
    }

    public List<Row> with(Status status) {
        return rows.stream().filter(r -> r.status() == status).toList();
    }
}
