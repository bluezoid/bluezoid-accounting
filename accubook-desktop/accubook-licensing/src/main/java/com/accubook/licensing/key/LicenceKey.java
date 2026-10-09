package com.accubook.licensing.key;

import java.util.Locale;

/**
 * A licence key: {@code ACB7-X92K-PQ41-M8ZT}.
 *
 * <p>An identifier and an activation credential, nothing more. It grants no
 * capability on its own - what the customer may actually do comes from the
 * signed entitlement the server issues in exchange for it. A key that leaks is
 * therefore a support problem, not a breach.
 *
 * <h2>Alphabet</h2>
 * Crockford-style Base32 without {@code I}, {@code L}, {@code O} or {@code U}.
 * The first three are excluded because customers read keys aloud over the phone
 * and confuse them with 1 and 0; {@code U} is excluded so the alphabet cannot
 * spell unfortunate words.
 *
 * <p>Input is normalised before validation - lower case accepted, and the
 * digits {@code 1}/{@code 0} folded to {@code I}/{@code O}'s intended values -
 * so a customer who mistypes the ambiguous characters still activates rather
 * than being told their valid key is wrong.
 *
 * <h2>Check character</h2>
 * The last character is a modulo-32 checksum of the preceding fifteen. It
 * catches a typo locally, before a network round trip, which turns "invalid
 * licence key, contact support" into "check the 7th character".
 */
public record LicenceKey(String normalised) {

    /** Excludes I, L, O, U. */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final int GROUPS = 4;
    private static final int GROUP_LENGTH = 4;
    private static final int TOTAL_LENGTH = GROUPS * GROUP_LENGTH;

    public LicenceKey {
        if (normalised == null || normalised.length() != TOTAL_LENGTH) {
            throw new IllegalArgumentException("Licence key must be " + TOTAL_LENGTH + " characters");
        }
    }

    /**
     * Parse user input in any plausible shape.
     *
     * @throws InvalidLicenceKeyException if the length, alphabet or check
     *                                    character is wrong
     */
    public static LicenceKey parse(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidLicenceKeyException("Enter a licence key.");
        }

        String cleaned = normalise(input);

        if (cleaned.length() != TOTAL_LENGTH) {
            throw new InvalidLicenceKeyException(
                    "A licence key has " + TOTAL_LENGTH + " characters; this one has " + cleaned.length() + ".");
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (ALPHABET.indexOf(cleaned.charAt(i)) < 0) {
                throw new InvalidLicenceKeyException(
                        "'" + cleaned.charAt(i) + "' is not valid in a licence key (position " + (i + 1) + ").");
            }
        }
        if (cleaned.charAt(TOTAL_LENGTH - 1) != checkCharacter(cleaned.substring(0, TOTAL_LENGTH - 1))) {
            throw new InvalidLicenceKeyException(
                    "This licence key looks mistyped. Please check it against the one you were sent.");
        }
        return new LicenceKey(cleaned);
    }

    /** True when {@code input} is a well-formed key. */
    public static boolean isValid(String input) {
        try {
            parse(input);
            return true;
        } catch (InvalidLicenceKeyException e) {
            return false;
        }
    }

    /**
     * Strip formatting and fold the characters excluded from the alphabet onto
     * the ones they are mistaken for.
     */
    static String normalise(String input) {
        StringBuilder out = new StringBuilder();
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '-', ' ', '\t', '_' -> { /* formatting, ignore */ }
                case 'I', 'L' -> out.append('1');
                case 'O' -> out.append('0');
                case 'U' -> out.append('V');
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Modulo-32 check character over the payload. */
    static char checkCharacter(String payload) {
        int sum = 0;
        for (int i = 0; i < payload.length(); i++) {
            int value = ALPHABET.indexOf(payload.charAt(i));
            // Position-weighted, so transposing two characters is detected.
            sum += value * (i + 1);
        }
        return ALPHABET.charAt(sum % ALPHABET.length());
    }

    /** Display form: {@code ACB7-X92K-PQ41-M8ZT}. */
    public String formatted() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < GROUPS; i++) {
            if (i > 0) {
                out.append('-');
            }
            out.append(normalised, i * GROUP_LENGTH, (i + 1) * GROUP_LENGTH);
        }
        return out.toString();
    }

    /**
     * The leading group, safe to show in logs and support tickets.
     *
     * <p>The full key is a credential, so it should not appear in a diagnostic
     * bundle the customer emails to us.
     */
    public String prefix() {
        return normalised.substring(0, GROUP_LENGTH);
    }

    @Override
    public String toString() {
        // Deliberately redacted: this type ends up in log lines.
        return prefix() + "-****-****-****";
    }
}
