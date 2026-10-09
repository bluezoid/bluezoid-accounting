package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A sales or purchase document as stored. {@code number} is null while DRAFT. */
public record Document(String id, DocumentKind kind, String number, String partyId, LocalDate date,
                       LocalDate dueDate, DocumentStatus status, String originalDocumentId,
                       BigDecimal amount, BigDecimal roundOff, BigDecimal total, String voucherId,
                       String cancellationVoucherId, String supplierDocumentNo, String narration,
                       List<DocumentLine> lines) {

    public Document {
        lines = List.copyOf(lines);
    }
}
