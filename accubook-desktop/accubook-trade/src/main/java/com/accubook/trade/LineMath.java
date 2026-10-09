package com.accubook.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A line's amount: quantity x rate, less a percentage discount (P7-9).
 *
 * <p>Rounded <strong>once</strong>, to paise, half-up - the figure printed on
 * the invoice. The reference never rounds (R-11): it keeps every digit of
 * {@code quantity * price * discount / 100} until Postgres silently rounds on
 * insert, while the header total is summed from the unrounded values, so the
 * two can disagree. Here the document total is the exact sum of rounded lines.
 */
public final class LineMath {

    private static final BigDecimal BASIS = BigDecimal.valueOf(10_000);

    private LineMath() {
    }

    /**
     * @param discountBp discount in basis points: 1250 is 12.5%
     */
    public static BigDecimal amount(BigDecimal quantity, BigDecimal rate, int discountBp) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new TradeException("A line needs a positive quantity");
        }
        if (rate == null || rate.signum() < 0) {
            throw new TradeException("A rate cannot be negative");
        }
        if (rate.stripTrailingZeros().scale() > 4) {
            throw new TradeException("A rate may have at most four decimal places; " + rate.toPlainString()
                    + " has more");
        }
        if (discountBp < 0 || discountBp > 10_000) {
            throw new TradeException("A discount must be between 0% and 100%");
        }
        // Multiply first, divide once, round once: rounding the discount and the
        // gross separately can disagree with rounding their difference.
        return quantity.multiply(rate)
                .multiply(BASIS.subtract(BigDecimal.valueOf(discountBp)))
                .divide(BASIS, 2, RoundingMode.HALF_UP);
    }
}
