package com.accubook.trade;

import com.accubook.tax.TaxCategory;

import java.math.BigDecimal;

/**
 * One line of a draft.
 *
 * @param itemId      null for a free-text line - the only kind a build without
 *                    inventory has
 * @param description shown on the document; defaults to the item's name
 * @param discountBp  basis points: 1000 is 10%
 * @param warehouseId required for goods, refused for services and free text
 * @param orderLineId the order line this line fulfils (P7-18), or null
 * @param taxCategory null to take the item's GST on the document date (or, on a
 *                    note, the original line's); required on a taxed free-text line
 * @param gstRate     percent; with {@code taxCategory}
 * @param cessRate    percent, or null for none; with {@code taxCategory}
 */
public record LineRequest(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                          int discountBp, String warehouseId, String orderLineId, TaxCategory taxCategory,
                          BigDecimal gstRate, BigDecimal cessRate) {

    /** A line whose GST comes from its item. */
    public LineRequest(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                       int discountBp, String warehouseId, String orderLineId) {
        this(itemId, description, quantity, rate, discountBp, warehouseId, orderLineId, null, null, null);
    }

    /** A line that fulfils no order. */
    public LineRequest(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                       int discountBp, String warehouseId) {
        this(itemId, description, quantity, rate, discountBp, warehouseId, null);
    }

    /** A free-text line - a service, a charge - with no item and no stock. */
    public static LineRequest text(String description, BigDecimal quantity, BigDecimal rate) {
        return new LineRequest(null, description, quantity, rate, 0, null);
    }

    /** A stocked item taken from or received into a warehouse. */
    public static LineRequest goods(String itemId, String warehouseId, BigDecimal quantity, BigDecimal rate) {
        return new LineRequest(itemId, null, quantity, rate, 0, warehouseId);
    }

    /** This line with its GST stated rather than defaulted. */
    public LineRequest withTax(TaxCategory category, BigDecimal gst, BigDecimal cess) {
        return new LineRequest(itemId, description, quantity, rate, discountBp, warehouseId, orderLineId, category,
                gst, cess);
    }
}
