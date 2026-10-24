package com.accubook.ui.api;

import java.math.BigDecimal;

/**
 * Formats money for display with Indian digit grouping: 12,34,567.00.
 *
 * <p>{@code DecimalFormat} cannot express it - its grouping is uniform, so the
 * en-IN locale still prints 1,234,567. Lakh and crore grouping is what an
 * Indian accountant reads without effort, so it is done by hand.
 *
 * <p><strong>Never rounds.</strong> Amounts are stored at four decimal places.
 * Showing at least two and as many more as the value actually carries means a
 * displayed column always adds up to its displayed total; rounding each line
 * for display would let them disagree on screen while the books are exact.
 */
public final class AmountFormat {

    private AmountFormat() {
    }

    public static String format(BigDecimal amount) {
        BigDecimal value = amount.stripTrailingZeros();
        if (value.scale() < 2) {
            value = value.setScale(2);
        }
        String plain = value.abs().toPlainString();
        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        String fraction = point < 0 ? "" : plain.substring(point);
        return (value.signum() < 0 ? "-" : "") + group(whole) + fraction;
    }

    private static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        int lead = rest.length() % 2;
        if (lead > 0) {
            grouped.append(rest, 0, lead);
        }
        for (int i = lead; i < rest.length(); i += 2) {
            if (!grouped.isEmpty()) {
                grouped.append(',');
            }
            grouped.append(rest, i, i + 2);
        }
        return grouped + "," + lastThree;
    }
}
