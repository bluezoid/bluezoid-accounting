package com.accubook.trade;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** A quotation's or order's contents. {@code endDate} is valid-until for a quotation, expected date for an order. */
public record OrderRequest(String partyId, LocalDate date, LocalDate endDate, String narration,
                           List<OrderLineRequest> lines) {

    public OrderRequest {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(date, "date");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static OrderRequest of(String partyId, LocalDate date, LocalDate endDate, List<OrderLineRequest> lines) {
        return new OrderRequest(partyId, date, endDate, null, lines);
    }
}
