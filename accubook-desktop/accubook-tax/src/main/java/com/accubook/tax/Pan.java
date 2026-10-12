package com.accubook.tax;

import java.util.Locale;
import java.util.Optional;

/**
 * A Permanent Account Number: five letters, four digits, a letter. The fourth
 * letter says who holds it - {@code P} an individual, {@code H} a Hindu
 * undivided family, {@code C} a company, {@code F} a firm, and so on - which
 * decides the rate of some TDS sections (194C: 1% or 2%).
 */
public final class Pan {

    private static final String FORMAT = "[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z]";

    private Pan() {
    }

    /** Why {@code pan} is not a PAN, or empty if it is one. */
    public static Optional<String> problem(String pan) {
        if (pan == null || pan.isBlank()) {
            return Optional.of("No PAN given");
        }
        String p = normalise(pan);
        if (!p.matches(FORMAT)) {
            return Optional.of("\"" + p + "\" is not a PAN: five letters (the fourth naming the holder's status), "
                    + "four digits, a letter");
        }
        return Optional.empty();
    }

    public static String normalise(String pan) {
        return pan.strip().toUpperCase(Locale.ROOT);
    }

    /** True for an individual ({@code P}) or a Hindu undivided family ({@code H}). */
    public static boolean individualOrHuf(String pan) {
        char status = normalise(pan).charAt(3);
        return status == 'P' || status == 'H';
    }
}
