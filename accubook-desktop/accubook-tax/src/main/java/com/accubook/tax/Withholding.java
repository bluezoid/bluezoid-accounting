package com.accubook.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * TDS or TCS on one document. Pure: the caller supplies what the party has
 * already had this year (or month) under the section.
 *
 * <h2>Thresholds</h2>
 * <ul>
 *   <li>{@code SINGLE_OR_ANNUAL}: taxed when this amount exceeds the single
 *       limit, or the year's total exceeds the annual limit. Crossing the annual
 *       limit also taxes the year's earlier amounts that were below it (P9-12) -
 *       the reference taxes the crossing bill alone (R-18).</li>
 *   <li>{@code MONTHLY}: the same, over the calendar month (194I from 1 Apr 2025).</li>
 *   <li>{@code ANNUAL_EXCESS}: only the part of the year's total above the limit (194Q).</li>
 *   <li>{@code NONE}: every amount.</li>
 * </ul>
 * "Exceeds" is strict: the sections exempt an amount that "does not exceed"
 * the limit. The reference taxes a single amount equal to it.
 *
 * <p>The amount is rounded half-up to the paisa (P9-11). Without a valid PAN
 * the section's no-PAN rate applies (s.206AA / 206CC).
 */
public final class Withholding {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Withholding() {
    }

    /**
     * What the party has had under the section in the period that applies -
     * the financial year, or the month for {@code MONTHLY} - before this document.
     *
     * @param base      the amounts
     * @param taxedBase the part of them already taxed
     */
    public record Prior(BigDecimal base, BigDecimal taxedBase) {
        public static final Prior NONE = new Prior(BigDecimal.ZERO, BigDecimal.ZERO);

        public BigDecimal untaxed() {
            return base.subtract(taxedBase);
        }
    }

    /**
     * @param taxedBase the base the rate was applied to - this amount, plus any
     *                  earlier untaxed amounts caught up
     */
    public record Result(WithholdingSection section, BigDecimal rate, BigDecimal base, BigDecimal taxedBase,
                         BigDecimal amount) {
    }

    /**
     * The section in force on {@code date} for {@code code}. A code split by
     * the deductee's status in the rule pack (194C into 194C-IND and 194C-OTH)
     * is resolved from the PAN; without a valid PAN, the "other" variant.
     */
    public static WithholdingSection resolve(RulePack rules, WithholdingSection.Kind kind, String code, String pan,
                                             LocalDate date) {
        if (code == null || code.isBlank()) {
            throw new RulePackException("Choose a " + kind + " section");
        }
        String c = code.strip().toUpperCase(java.util.Locale.ROOT);
        Optional<WithholdingSection> exact = rules.section(kind, c, date);
        if (exact.isPresent()) {
            return exact.get();
        }
        boolean individual = Pan.problem(pan).isEmpty() && Pan.individualOrHuf(pan);
        Optional<WithholdingSection> split = rules.section(kind, c + (individual ? "-IND" : "-OTH"), date);
        return split.orElseThrow(() -> new RulePackException(kind + " section " + c + " is not in force on " + date
                + " (rule pack " + rules.version() + ")"));
    }

    public static Result compute(WithholdingSection section, String pan, BigDecimal base, Prior prior) {
        if (base.signum() < 0) {
            throw new IllegalArgumentException("A withholding base cannot be negative");
        }
        BigDecimal rate = Pan.problem(pan).isEmpty() ? section.rate() : section.noPanRate();
        BigDecimal taxed = switch (section.thresholdType()) {
            case NONE -> base;
            case SINGLE_OR_ANNUAL -> {
                boolean annual = section.annualThreshold() != null
                        && prior.base().add(base).compareTo(section.annualThreshold()) > 0;
                boolean single = section.threshold() != null && base.compareTo(section.threshold()) > 0;
                yield annual ? base.add(prior.untaxed()) : single ? base : BigDecimal.ZERO;
            }
            case MONTHLY -> prior.base().add(base).compareTo(section.threshold()) > 0
                    ? base.add(prior.untaxed()) : BigDecimal.ZERO;
            case ANNUAL_EXCESS -> excess(prior.base().add(base), section.annualThreshold())
                    .subtract(excess(prior.base(), section.annualThreshold()));
        };
        BigDecimal amount = taxed.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new Result(section, rate.stripTrailingZeros(), base, taxed, amount);
    }

    private static BigDecimal excess(BigDecimal total, BigDecimal limit) {
        return total.subtract(limit).max(BigDecimal.ZERO);
    }

    /** The first day of the financial year (April) containing {@code date}. */
    public static LocalDate yearStart(LocalDate date) {
        return LocalDate.of(date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1, 4, 1);
    }
}
