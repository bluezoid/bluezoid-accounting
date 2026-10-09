package com.accubook.trade;

import java.math.BigDecimal;

public record DocumentLine(String id, int sequence, String itemId, String description, BigDecimal quantity,
                           BigDecimal rate, int discountBp, BigDecimal amount, String warehouseId) {
}
