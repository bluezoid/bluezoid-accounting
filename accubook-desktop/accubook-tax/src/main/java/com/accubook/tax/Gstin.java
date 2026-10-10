package com.accubook.tax;

import java.util.Locale;
import java.util.Optional;

/**
 * GSTIN validation: format, state and check digit.
 *
 * <p>The reference has a correct, tested Mod-36 check-digit verifier that no
 * production path calls - only the format regex is used, so a mistyped GSTIN
 * with a valid shape is accepted and reaches the returns. Here all three checks
 * run together.
 */
public final class Gstin {

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String FORMAT = "\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]";

    private Gstin() {
    }

    /**
     * @return why {@code gstin} is invalid, or empty when it is valid
     */
    public static Optional<String> problem(String gstin, RulePack rules) {
        if (gstin == null || gstin.isBlank()) {
            return Optional.of("A GSTIN is required");
        }
        String value = normalise(gstin);
        if (!value.matches(FORMAT)) {
            return Optional.of("\"" + gstin.strip() + "\" is not in GSTIN format (15 characters: state code, PAN, "
                    + "entity number, Z, check character)");
        }
        if (rules.state(value.substring(0, 2)).isEmpty()) {
            return Optional.of("\"" + value + "\" starts with " + value.substring(0, 2) + ", which is not a state code");
        }
        char expected = checkCharacter(value.substring(0, 14));
        if (value.charAt(14) != expected) {
            return Optional.of("\"" + value + "\" fails the GSTIN check character - it is probably mistyped");
        }
        return Optional.empty();
    }

    public static String normalise(String gstin) {
        return gstin.strip().toUpperCase(Locale.ROOT);
    }

    /** The two-digit state a GSTIN is registered in. */
    public static String stateOf(String gstin) {
        return normalise(gstin).substring(0, 2);
    }

    /** The PAN embedded in a GSTIN, characters 3 to 12. */
    public static String panOf(String gstin) {
        return normalise(gstin).substring(2, 12);
    }

    /** Mod-36 check character over the first 14 characters (factors alternate 1, 2). */
    static char checkCharacter(String first14) {
        int sum = 0;
        for (int i = 0; i < first14.length(); i++) {
            int value = CHARS.indexOf(first14.charAt(i));
            int product = value * (i % 2 == 0 ? 1 : 2);
            sum += product / 36 + product % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }
}
