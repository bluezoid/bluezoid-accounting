package com.accubook.platform.money;

/**
 * The fixed decimal scale of a stored numeric column.
 *
 * <p>Decision B-3 (approved 2026-09-01). SQLite has no decimal type: a column
 * declared {@code DECIMAL(18,4)} is stored as an IEEE-754 {@code real}, and
 * {@code 0.1 + 0.2 = 0.3} evaluates to FALSE. Money is therefore stored as a
 * scaled {@code INTEGER} (minor units) and converted at exactly one boundary,
 * {@link MoneyMapper}.
 *
 * <p>A single global scale does not work, which is why this is an enum rather
 * than a constant:
 * <ul>
 *   <li>scale 8 everywhere caps money at ~Rs 9,223 crore, so a Rs 10,000 crore
 *       turnover would overflow {@code long};</li>
 *   <li>scale 4 everywhere leaves exchange rates with only 4 decimal places.</li>
 * </ul>
 *
 * <p>Each constant mirrors a column class in the reference application's
 * PostgreSQL schema, so ported values keep their original precision.
 */
public enum Scale {

    /** Amounts, balances, totals, tax amounts. Mirrors {@code Decimal(18,4)}. */
    MONEY(4, "_e4"),

    /** Percentages held as basis points: 18% is stored as 1800. Mirrors {@code Decimal(5,2)}. */
    RATE(2, "_bp"),

    /** Exchange rates. Mirrors {@code Decimal(18,8)}. */
    FX(8, "_e8"),

    /** Unit-of-measure conversion factors. Mirrors {@code Decimal(18,6)}. */
    CONVERSION(6, "_e6"),

    /** Leave and attendance days, to half-day granularity. Mirrors {@code Decimal(4,1)}. */
    DAYS(1, "_e1");

    private final int digits;
    private final String suffix;

    Scale(int digits, String suffix) {
        this.digits = digits;
        this.suffix = suffix;
    }

    /** Number of decimal digits retained. */
    public int digits() {
        return digits;
    }

    /**
     * Conventional column-name suffix, e.g. {@code total_amount_e4}.
     *
     * <p>The suffix is not decoration: it makes the encoding visible in every
     * ad-hoc query, so nobody reads a raw {@code 1234500} as rupees.
     */
    public String columnSuffix() {
        return suffix;
    }
}
