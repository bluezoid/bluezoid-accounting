package com.accubook.inventory;

import com.accubook.tax.TaxCategory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An item's GST from a date until the next entry (P9-2).
 *
 * @param gstRate         percent, zero unless taxable
 * @param cessRate        percent of the taxable value; zero when none
 * @param hsnSac          the item's HSN or SAC code, read from the item
 * @param rulePackVersion the rule pack the rate was checked against
 */
public record ItemTaxRate(LocalDate effectiveFrom, TaxCategory category, BigDecimal gstRate, BigDecimal cessRate,
                          String hsnSac, String rulePackVersion) {
}
