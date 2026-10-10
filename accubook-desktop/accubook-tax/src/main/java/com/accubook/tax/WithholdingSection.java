package com.accubook.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A TDS or TCS section in force between two dates.
 *
 * <p>The reference's table has no dates at all, so a prior year recomputed today
 * takes today's rates - and its "FY 2025-26" values are already stale (R-17).
 *
 * @param rate          percent, e.g. 1 or 0.1
 * @param noPanRate     percent when the deductee has no PAN
 * @param thresholdType how {@code threshold} and {@code annualThreshold} apply
 */
public record WithholdingSection(Kind kind, String section, LocalDate from, LocalDate to, BigDecimal rate,
                                 BigDecimal noPanRate, ThresholdType thresholdType, BigDecimal threshold,
                                 BigDecimal annualThreshold, String description) {

    public enum Kind { TDS, TCS }

    public enum ThresholdType {
        /** Tax the full amount once a single payment reaches {@code threshold} or the year's total exceeds {@code annualThreshold}. */
        SINGLE_OR_ANNUAL,
        /** Tax only the part of the year's total above {@code annualThreshold} (194Q). */
        ANNUAL_EXCESS,
        /** Tax the full amount when a month's payments exceed {@code threshold} (194I from 1 Apr 2025). */
        MONTHLY,
        /** No threshold: every amount. */
        NONE
    }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
