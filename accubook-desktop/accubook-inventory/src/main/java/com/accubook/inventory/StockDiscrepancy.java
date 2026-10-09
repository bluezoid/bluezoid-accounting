package com.accubook.inventory;

import java.math.BigDecimal;

/**
 * A place where the cached quantity disagrees with the movement log.
 *
 * <p>The log is the record; {@code stock} is a cache kept so a sale has a row to
 * decrement under a guard. A disagreement means the cache was damaged, and the
 * log's figure is the right one.
 */
public record StockDiscrepancy(String itemId, String warehouseId, BigDecimal cached, BigDecimal fromMovements) {
}
