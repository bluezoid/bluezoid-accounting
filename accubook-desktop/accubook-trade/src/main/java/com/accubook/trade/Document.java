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
 */
public record Document(String id, DocumentKind kind, String number, String partyId, LocalDate date,
                       LocalDate dueDate, DocumentStatus status, String originalDocumentId,
                       BigDecimal amount, BigDecimal roundOff, BigDecimal total, String voucherId,
                       String cancellationVoucherId, String supplierDocumentNo, String narration,
                       List<DocumentLine> lines, BigDecimal taxable, BigDecimal tax, GstBasis gst) {

    public Document {
        lines = List.copyOf(lines);
    }
}
