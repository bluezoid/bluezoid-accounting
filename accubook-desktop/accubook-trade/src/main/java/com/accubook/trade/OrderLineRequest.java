package com.accubook.trade;

import java.math.BigDecimal;

/**
 * One line of a quotation or order. Always an item: orders require inventory (B-7).
 *
 * @param warehouseId proposed for the invoice or bill made from it; optional
 */
public record OrderLineRequest(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                               int discountBp, String warehouseId) {

    public static OrderLineRequest of(String itemId, BigDecimal quantity, BigDecimal rate) {
        return new OrderLineRequest(itemId, null, quantity, rate, 0, null);
    }
}
