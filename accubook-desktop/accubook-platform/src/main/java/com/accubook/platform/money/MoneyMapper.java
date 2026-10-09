package com.accubook.platform.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one and only conversion boundary between {@link BigDecimal} (used
 * everywhere in the domain and UI) and the scaled {@code long} minor units
 * actually stored in SQLite.
 *
 * <p><strong>No other class may convert.</strong> Scale bugs in accounting
 * software are silent and cumulative, and they get in precisely when conversion
 * is duplicated at call sites. Keeping it here means there is exactly one place
 * to review and one place to test.
 *
 * <h2>Why {@code longValueExact}</h2>
 * A value carrying more precision than its column can hold is a programming
 * error, not something to round away silently. {@code longValueExact()} throws
 * rather than truncating, so the mistake surfaces at the write, not months later
 * in a reconciliation.
 *
 * <h2>Two things this class cannot protect you from</h2>
 * <ul>
 *   <li><strong>Never scale back in SQL.</strong> {@code amount_e4 / 10000.0} is
 *       float division; it printed {@code ...477.6000} for a value that is
 *       exactly {@code ...477.5807}. Read the {@code long} and use
 *       {@link #fromStored}.</li>
 *   <li><strong>Never multiply money by a rate in SQL.</strong>
 *       {@code money_e4 * fx_e8} reaches ~8.31e19, overflowing {@code long} by
 *       about 9x, and SQLite silently degrades the result to a float. Use
 *       {@link #applyRate} or {@link #convert}.</li>
 * </ul>
 */
public final class MoneyMapper {

    private MoneyMapper() {
    }

    /**
     * Convert a domain value to its stored representation.
     *
     * @throws ArithmeticException if {@code value} has more decimal digits than
     *                             {@code scale} allows, or does not fit in a
     *                             {@code long}
     * @throws NullPointerException if {@code value} or {@code scale} is null
     */
    public static long toStored(BigDecimal value, Scale scale) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (scale == null) {
            throw new NullPointerException("scale");
        }
        return value.movePointRight(scale.digits()).longValueExact();
    }

    /** Convert a stored value back to a {@link BigDecimal} at the column's scale. */
    public static BigDecimal fromStored(long stored, Scale scale) {
        if (scale == null) {
            throw new NullPointerException("scale");
        }
        return BigDecimal.valueOf(stored, scale.digits());
    }

    /**
     * Round a value to a scale explicitly, for the cases where rounding is the
     * intended behaviour rather than an error.
     *
     * <p>{@code RoundingMode} is a required argument on purpose. An implicit
     * default is how rounding policy drifts between call sites, and in a tax
     * context that difference is a compliance question, not a detail.
     */
    public static BigDecimal round(BigDecimal value, Scale scale, RoundingMode mode) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        if (mode == null) {
            throw new NullPointerException("roundingMode");
        }
        return value.setScale(scale.digits(), mode);
    }

    /**
     * Apply a percentage rate held in basis points to a money amount.
     *
     * <p>Exists so the multiplication happens in {@link BigDecimal} rather than
     * in SQL, where it would overflow. The result is <em>not</em> rounded: GST
     * aggregation in the reference application is sum-then-round, and rounding
     * here would change every downstream total. Callers round at the point the
     * policy says to.
     */
    public static BigDecimal applyRate(BigDecimal amount, BigDecimal rateInPercent) {
        if (amount == null) {
            throw new NullPointerException("amount");
        }
        if (rateInPercent == null) {
            throw new NullPointerException("rateInPercent");
        }
        return amount.multiply(rateInPercent).movePointLeft(2);
    }

    /**
     * Convert an amount using an exchange rate.
     *
     * <p>Same reasoning as {@link #applyRate}: kept out of SQL because the
     * intermediate product overflows a 64-bit integer. Unrounded for the same
     * reason.
     */
    public static BigDecimal convert(BigDecimal amount, BigDecimal fxRate) {
        if (amount == null) {
            throw new NullPointerException("amount");
        }
        if (fxRate == null) {
            throw new NullPointerException("fxRate");
        }
        return amount.multiply(fxRate);
    }

    /**
     * Largest magnitude representable at the given scale.
     *
     * <p>At {@link Scale#MONEY} this is about Rs 922 lakh crore, roughly three
     * times India's GDP, so overflow is not a practical concern for a single
     * company's books. Exposed so bounds can be asserted in tests and CHECK
     * constraints.
     */
    public static BigDecimal maxValue(Scale scale) {
        return BigDecimal.valueOf(Long.MAX_VALUE, scale.digits());
    }

    /** Smallest (most negative) magnitude representable at the given scale. */
    public static BigDecimal minValue(Scale scale) {
        return BigDecimal.valueOf(Long.MIN_VALUE, scale.digits());
    }
}
