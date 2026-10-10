package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A sales or purchase order. {@code quotationId} is set when a sales order came from a quotation. */
public record Order(String id, String number, String partyId, LocalDate date, LocalDate expectedDate,
                    String quotationId, OrderStatus status, String closeReason, BigDecimal amount,
                    List<OrderLine> lines) {

    public Order {
        lines = List.copyOf(lines);
    }
}
