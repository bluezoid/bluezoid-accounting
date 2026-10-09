package com.accubook.inventory;

import java.math.BigDecimal;

public record StockLevel(String itemId, String warehouseId, BigDecimal quantity) {
}
