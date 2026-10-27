package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A posted invoice or bill - or an opening bill from before the books (S2-4) -
 * with money still owed on it at some date.
 */
public record OutstandingDocument(String documentId, String number, String partyId, LocalDate date,
                                  LocalDate dueDate, BigDecimal total, BigDecimal outstanding, long daysPastDue,
                                  AgingBucket bucket, boolean openingBill) {
}
