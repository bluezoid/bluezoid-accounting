package com.accubook.trade;

import java.time.LocalDate;

/**
 * Document numbers: {@code INV/26-27/00001} (P7-8).
 *
 * <p>The series is per kind and per fiscal year <em>of the document date</em>.
 * The reference picks the year from today's date with April hard-coded
 * (R-9), so a March invoice entered in April joins next year's series.
 *
 * <p>CGST Rule 46(b) allows at most sixteen characters. The reference's
 * {@code INV/2026-27/00001} has seventeen (R-10). The format here is fifteen
 * and grows only to sixteen; a number that would exceed it is refused.
 */
public final class DocumentNumbers {

    /** CGST Rules, Rule 46(b). */
    public static final int MAX_LENGTH = 16;

    private DocumentNumbers() {
    }

    public static String format(DocumentKind kind, LocalDate fiscalYearStart, LocalDate fiscalYearEnd, long sequence) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("A sequence starts at 1");
        }
        String number = kind.numberPrefix() + "/" + yearLabel(fiscalYearStart, fiscalYearEnd)
                + "/" + String.format("%05d", sequence);
        if (number.length() > MAX_LENGTH) {
            throw new TradeException("The " + kind.numberPrefix() + " series for this year is full: "
                    + number + " would exceed the " + MAX_LENGTH + "-character limit for invoice numbers.");
        }
        return number;
    }

    /** {@code 26-27} for April-March; {@code 26} for a year inside one calendar year. */
    static String yearLabel(LocalDate start, LocalDate end) {
        String first = two(start.getYear());
        return start.getYear() == end.getYear() ? first : first + "-" + two(end.getYear());
    }

    private static String two(int year) {
        return String.format("%02d", year % 100);
    }

    /** Counter scope in {@code number_counter}: one series per kind and fiscal year. */
    static String scope(DocumentKind kind, String fiscalYearId) {
        return "DOC:" + kind.name() + ":" + fiscalYearId;
    }
}
