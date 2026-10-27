package com.accubook.ui.api;

import java.math.BigDecimal;

/**
 * Reads an amount typed by a person: {@code 1,23,456.78}, {@code 123,456.78},
 * {@code ₹ 500}, {@code 500.5}. Commas must group as Indian or western figures
 * do - a comma out of place is a mistyped amount, refused rather than read as
 * something ten times larger. Anything else that is not a number is refused
 * with a message to show.
 *
 * <p><strong>Never rounds.</strong> More decimal places than the field allows
 * is an error, not a silent rounding: {@code 10.005} in a money field is a
 * typing mistake the person should see.
 */
public final class AmountInput {

    /** 1,23,45,678: three digits last, pairs before. */
    private static final String INDIAN = "\\d{1,2}(,\\d{2})*,\\d{3}";
    /** 12,345,678: threes throughout. */
    private static final String WESTERN = "\\d{1,3}(,\\d{3})+";

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
        String whole = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (whole.contains(",") && !whole.matches(INDIAN) && !whole.matches(WESTERN)) {
            throw new IllegalArgumentException("'" + text.strip() + "' has its commas in the wrong places");
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
