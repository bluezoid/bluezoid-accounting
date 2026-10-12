package com.accubook.trade;

import com.accubook.tax.LineTax;
import com.accubook.tax.TaxCategory;

import java.math.BigDecimal;
import java.util.List;

/**
 * A stored line. {@code amount} is its taxable value.
 *
 * @param taxCategory null on a line written before P9b, or on an untaxed document
 *                    that stated none
 * @param taxes       the line's GST components, empty when it bears none
 * @param uqc         the GST unit code it was drafted with, or null
 */
public record DocumentLine(String id, int sequence, String itemId, String description, BigDecimal quantity,
                           BigDecimal rate, int discountBp, BigDecimal amount, String warehouseId,
                           String orderLineId, TaxCategory taxCategory, BigDecimal gstRate, BigDecimal cessRate,
                           String hsnSac, List<LineTax> taxes, String uqc) {

    public DocumentLine {
        taxes = List.copyOf(taxes);
    }

    public BigDecimal tax() {
        return taxes.stream().map(LineTax::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
