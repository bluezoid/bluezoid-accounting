package com.accubook.ui.api;

import java.math.BigDecimal;

/**
 * Reads an amount typed by a person: {@code 1,23,456.78}, {@code ₹ 500},
 * {@code 500.5}. Grouping commas are ignored wherever they fall; anything else
 * that is not a number is refused with a message to show.
 *
 * <p><strong>Never rounds.</strong> More decimal places than the field allows
 * is an error, not a silent rounding: {@code 10.005} in a money field is a
 * typing mistake the person should see.
 */
public final class AmountInput {

    private final int maxScale;
    private final boolean negativeAllowed;

    private AmountInput(int maxScale, boolean negativeAllowed) {
        this.maxScale = maxScale;
        this.negativeAllowed = negativeAllowed;
    }

    /** Money: two decimal places, not negative. */
    public static AmountInput money() {
        return new AmountInput(2, false);
    }

    /** Money that may be negative, such as an opening difference. */
    public static AmountInput signedMoney() {
        return new AmountInput(2, true);
    }

    /** A quantity or a rate: up to {@code scale} places, not negative. */
    public static AmountInput ofScale(int scale) {
        return new AmountInput(scale, false);
    }

    /** The value, or null for a blank field. */
    public BigDecimal parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip().replace("₹", "").replace("Rs.", "").strip();
        boolean negative = s.startsWith("-");
        if (negative) {
            s = s.substring(1).strip();
        }
        if (!s.matches("\\d[\\d,]*(\\.\\d*)?|\\.\\d+") || s.endsWith(",") || s.contains(",.")) {
            throw new IllegalArgumentException("'" + text.strip() + "' is not an amount");
        }
        BigDecimal value = new BigDecimal(s.replace(",", "").replaceAll("\\.$", ""));
        if (value.scale() > maxScale) {
            throw new IllegalArgumentException("At most " + maxScale + " decimal places");
        }
        if (negative && !negativeAllowed) {
            throw new IllegalArgumentException("An amount here cannot be negative");
        }
        return negative ? value.negate() : value;
    }
}
