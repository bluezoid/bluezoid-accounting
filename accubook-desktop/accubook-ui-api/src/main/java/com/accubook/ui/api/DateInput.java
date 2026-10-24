package com.accubook.ui.api;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Reads a date typed by a person, day first as written in India (S-4):
 * {@code 15-04-2026}, {@code 15/4/26}, {@code 15.4}, {@code 15}, or {@code t}
 * for today. A missing year is taken from the open fiscal year, so 15-1 typed
 * in FY 2026-27 is 15 January 2027; a missing month is the current month.
 */
public final class DateInput {

    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private DateInput() {
    }

    public static String format(LocalDate date) {
        return date == null ? "" : FORMAT.format(date);
    }

    /**
     * The date, or null for a blank field.
     *
     * @param today           what {@code t} and a bare day refer to
     * @param fiscalYearStart the first day of the open fiscal year
     */
    public static LocalDate parse(String text, LocalDate today, LocalDate fiscalYearStart) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.strip();
        if (s.equalsIgnoreCase("t")) {
            return today;
        }
        if (!s.matches("\\d{1,2}([-/.]\\d{1,2}([-/.](\\d{2}|\\d{4}))?)?")) {
            throw new IllegalArgumentException("'" + s + "' is not a date; type it as dd-mm-yyyy");
        }
        String[] parts = s.split("[-/.]");
        int day = Integer.parseInt(parts[0]);
        int month = parts.length > 1 ? Integer.parseInt(parts[1]) : today.getMonthValue();
        int year;
        if (parts.length > 2) {
            year = Integer.parseInt(parts[2]);
            if (parts[2].length() == 2) {
                year += 2000;
            }
        } else if (parts.length > 1) {
            year = month >= fiscalYearStart.getMonthValue() ? fiscalYearStart.getYear()
                    : fiscalYearStart.getYear() + 1;
        } else {
            year = today.getYear();
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("'" + s + "' is not a date in the calendar");
        }
    }
}
