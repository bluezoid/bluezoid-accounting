package com.accubook.trade;

import java.math.BigDecimal;

/**
 * A quotation or order line. For an order, {@code fulfilled} is derived from
 * posted invoice or bill lines linked to it (R-14); for a quotation it is zero.
 */
public record OrderLine(String id, int sequence, String itemId, String description, BigDecimal quantity,
                        BigDecimal rate, int discountBp, BigDecimal amount, String warehouseId,
                        BigDecimal fulfilled) {

    public BigDecimal open() {
        return quantity.subtract(fulfilled).max(BigDecimal.ZERO);
    }
}
