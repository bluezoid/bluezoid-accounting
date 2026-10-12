package com.accubook.inventory;

/**
 * A unit of measure, per company.
 *
 * @param decimals how many decimal places a quantity in this unit may carry -
 *                 0 for pieces, 3 for kilograms
 * @param uqc      the GST unit quantity code returns report it as, or null (P9-16)
 */
public record Unit(String id, String symbol, String name, int decimals, String uqc) {

    public Unit(String id, String symbol, String name, int decimals) {
        this(id, symbol, name, decimals, null);
    }
}
