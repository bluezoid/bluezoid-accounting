package com.accubook.trade;

import java.math.BigDecimal;

/**
 * One line of a draft.
 *
 * @param itemId      null for a free-text line - the only kind a build without
 *                    inventory has
 * @param description shown on the document; defaults to the item's name
 * @param discountBp  basis points: 1000 is 10%
 * @param warehouseId required for goods, refused for services and free text
 */
public record LineRequest(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                          int discountBp, String warehouseId) {

    /** A free-text line - a service, a charge - with no item and no stock. */
    public static LineRequest text(String description, BigDecimal quantity, BigDecimal rate) {
        return new LineRequest(null, description, quantity, rate, 0, null);
    }

    /** A stocked item taken from or received into a warehouse. */
    public static LineRequest goods(String itemId, String warehouseId, BigDecimal quantity, BigDecimal rate) {
        return new LineRequest(itemId, null, quantity, rate, 0, warehouseId);
    }
}
