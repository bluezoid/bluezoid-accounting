package com.accubook.tax;

import java.math.BigDecimal;

/**
 * One component of one line's tax.
 *
 * @param rate   percent, e.g. 9 for CGST on an 18% line
 * @param amount rounded half-up to the paisa (B-4)
 */
public record LineTax(GstComponent component, BigDecimal rate, BigDecimal amount) {
}
