package com.accubook.trade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding a document's total to the nearest rupee (P7-10).
 *
 * @param total    what the party pays or is paid
 * @param roundOff {@code total - amount}: positive when rounded up, never more
 *                 than half a rupee either way
 */
public record RoundOff(BigDecimal amount, BigDecimal total, BigDecimal roundOff) {

    public static RoundOff of(BigDecimal amount, boolean enabled) {
        BigDecimal exact = amount.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal total = enabled ? exact.setScale(0, RoundingMode.HALF_UP).setScale(2) : exact;
        return new RoundOff(exact, total, total.subtract(exact));
    }
}
