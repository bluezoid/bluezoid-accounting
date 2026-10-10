package com.accubook.inventory;

import java.math.BigDecimal;

/** What one item in one warehouse was worth on a date. */
public record ValuationLine(String itemId, String warehouseId, BigDecimal quantity, BigDecimal value) {
}
