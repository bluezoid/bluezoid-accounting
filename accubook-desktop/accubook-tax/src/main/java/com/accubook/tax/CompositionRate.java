package com.accubook.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A composition category's rate between two dates.
 *
 * @param rate percent of the base, half as CGST and half as SGST
 * @param base {@code TAXABLE} - taxable supplies only; {@code TURNOVER} - all supplies in the state
 */
public record CompositionRate(CompositionCategory category, LocalDate from, LocalDate to, BigDecimal rate,
                              Base base) {

    public enum Base { TAXABLE, TURNOVER }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
