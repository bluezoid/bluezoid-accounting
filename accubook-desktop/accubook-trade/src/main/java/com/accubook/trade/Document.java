package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A sales or purchase document as stored. {@code number} is null while DRAFT.
 *
 * @param amount  what the party is charged before round-off: {@code taxable + tax},
 *                or {@code taxable} alone under reverse charge
 * @param gst     null only for a document written before P9b
 * @param withholdingSection the TDS or TCS section as resolved at posting (e.g. 194C-OTH), or null
 * @param tds     TDS deducted (purchase) - the vendor settles {@code total - tds}
 * @param tcs     TCS collected (sales) - the customer settles {@code total + tcs}
 */
public record Document(String id, DocumentKind kind, String number, String partyId, LocalDate date,
                       LocalDate dueDate, DocumentStatus status, String originalDocumentId,
                       BigDecimal amount, BigDecimal roundOff, BigDecimal total, String voucherId,
                       String cancellationVoucherId, String supplierDocumentNo, String narration,
                       List<DocumentLine> lines, BigDecimal taxable, BigDecimal tax, GstBasis gst,
                       String withholdingSection, BigDecimal tds, BigDecimal tcs) {

    public Document {
        lines = List.copyOf(lines);
    }

    /** What the party settles: the total less TDS, plus TCS. */
    public BigDecimal settle() {
        return total.subtract(tds).add(tcs);
    }
}
