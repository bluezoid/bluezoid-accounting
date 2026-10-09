package com.accubook.inventory;

/**
 * A unit of measure, per company.
 *
 * @param decimals how many decimal places a quantity in this unit may carry -
 *                 0 for pieces, 3 for kilograms
 */
public record Unit(String id, String symbol, String name, int decimals) {
}
