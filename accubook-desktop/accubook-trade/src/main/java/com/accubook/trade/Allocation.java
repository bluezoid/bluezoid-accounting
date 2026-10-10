package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One live or removed allocation. {@code removedDate} is null while it stands. */
public record Allocation(String id, SourceType sourceType, String sourceId, String documentId, BigDecimal amount,
                         LocalDate effectiveDate, LocalDate removedDate) {

    /** What paid: a receipt or payment, or a credit or debit note. */
    public enum SourceType { SETTLEMENT, NOTE }
}
