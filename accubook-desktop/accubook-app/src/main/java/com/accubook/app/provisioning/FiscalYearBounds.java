package com.accubook.app.provisioning;

import java.time.LocalDate;

/**
 * The fiscal year containing a given date.
 *
 * <p>Uses {@link LocalDate} throughout. A fiscal year boundary is a calendar
 * date, not an instant, and treating it as one is how the reference application
 * ends up storing 31 March 18:30Z and pushing every 1 April voucher into the
 * previous year on an IST deployment. With {@code LocalDate} that class of bug
 * cannot occur.
 *
 * <p>The start month is a parameter rather than a constant: April suits India,
 * but the same product should not lie to a company on a January or July year.
 * The reference hardcodes {@code getMonth() >= 3} in its balance sheet while its
 * trial balance honours the configured year, so the two reports disagree.
 */
public record FiscalYearBounds(String name, LocalDate startDate, LocalDate endDate) {

    /**
     * The fiscal year containing {@code on}, for a year beginning in
     * {@code startMonth}.
     *
     * @param startMonth 1-12; 4 is the Indian default
     */
    public static FiscalYearBounds forDate(LocalDate on, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("Fiscal year start month must be 1-12, was " + startMonth);
        }
        int startYear = on.getMonthValue() >= startMonth ? on.getYear() : on.getYear() - 1;
        LocalDate start = LocalDate.of(startYear, startMonth, 1);
        LocalDate end = start.plusYears(1).minusDays(1);

        // A year beginning in January is named for that single year; any other
        // start straddles two, which is what "FY 2025-26" conveys.
        String name = startMonth == 1
                ? "FY " + startYear
                : "FY " + startYear + "-" + String.format("%02d", (startYear + 1) % 100);

        return new FiscalYearBounds(name, start, end);
    }

    /** True when {@code date} falls within this year, inclusive of both ends. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
