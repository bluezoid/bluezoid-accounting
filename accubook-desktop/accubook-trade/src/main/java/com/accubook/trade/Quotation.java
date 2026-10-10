package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A quotation as seen on a given day: {@code status} is EXPIRED once past valid-until and not yet decided. */
public record Quotation(String id, String number, String partyId, LocalDate date, LocalDate validUntil,
                        QuotationStatus status, BigDecimal amount, List<OrderLine> lines) {

    public Quotation {
        lines = List.copyOf(lines);
    }
}
