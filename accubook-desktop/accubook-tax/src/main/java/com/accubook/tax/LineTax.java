package com.accubook.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One component of one line's tax.
 *
 * @param rate   percent, e.g. 9 for CGST on an 18% line
 * @param amount rounded half-up to the paisa (B-4)
 */
public record LineTax(GstComponent component, BigDecimal rate, BigDecimal amount) {

    /** Normalised, so two equal taxes are equal records however they were read or computed. */
    public LineTax {
        rate = rate.stripTrailingZeros();
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
